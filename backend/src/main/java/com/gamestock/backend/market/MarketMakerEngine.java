package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.MarketEnvironment.clamp;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

/** RiskBook is reconstructed from persistent allocations and actual fills. */
public final class MarketMakerEngine {
    private static final int QUOTE_LEVELS = 4;
    private static final int EMERGENCY_QUOTE_SIZE = 4;
    public record RiskBook(long cashBudget,int inventory,int targetInventory,int maxInventory,long riskLimit) {}
    public record Quote(String side,double price,int quantity) {}
    /** External best quotes exclude this LP's orders being replaced; zero means no quote. */
    public record QuoteConstraints(long lowerPrice,long upperPrice,long bestExternalBid,long bestExternalAsk) {}

    /** Cash needed to keep the four-level emergency bid ladder alive at a conservative price. */
    static long emergencyCashRequirement(long maxQuotePrice) {
        long gross=Math.multiplyExact(Math.max(1,maxQuotePrice),(long)EMERGENCY_QUOTE_SIZE);
        return Math.multiplyExact(QUOTE_LEVELS,gross+BatchOrderBook.fee(gross));
    }

    private static double positive(double value,double fallback) {
        return Double.isFinite(value)&&value>0?value:fallback;
    }

    /**
     * Keep the quote center tied to a slowly moving value as well as recent prints.
     * The hidden fundamental is bounded by the observed ten-minute VWAP so a stale
     * news/value update cannot create a sudden jump, while a one-sided low print
     * cannot walk the LP ladder down forever.
     */
    public double fairValue(PriceMetricService.Metrics m,double fundamental) {
        double referenceVwap=positive(m.referenceVwap(),m.lastPrice());
        double boundedFundamental=positive(fundamental,referenceVwap);
        boundedFundamental=clamp(boundedFundamental,referenceVwap*.75,referenceVwap*1.25);
        return .40*boundedFundamental+.35*referenceVwap+.20*positive(m.vwap(),referenceVwap)
                +.05*positive(m.lastPrice(),referenceVwap);
    }

    public double reservationPrice(PriceMetricService.Metrics m,RiskBook risk,double tick) {
        return reservationPrice(m,risk,tick,m.referenceVwap());
    }

    public double reservationPrice(PriceMetricService.Metrics m,RiskBook risk,double tick,double fundamental) {
        // Recent fills matter, but they must not be the only source of the next quote center.
        double tradeAnchor=fairValue(m,fundamental);
        double bookReference=clamp((m.midPrice()+m.microPrice())/2,tradeAnchor*.9975,tradeAnchor*1.0025);
        double reference=.65*tradeAnchor+.35*bookReference;
        double inventoryRatio=(risk.inventory()-risk.targetInventory())/(double)Math.max(1,risk.targetInventory());
        // Low cash also makes selling easier. These are quote incentives, never forced executions.
        double cashPressure=Math.max(0,1-risk.cashBudget()/Math.max(1.0,risk.targetInventory()*reference*.3));
        double skew=clamp((inventoryRatio+cashPressure)*Math.max(tick,reference*(.0005+m.volatility()*.25)),
                -reference*.0015,reference*.0015);
        return reference+clamp(m.return5s(),-.002,.002)*reference*.1-skew;
    }

    public double halfSpread(PriceMetricService.Metrics m,MarketEnvironment e,RiskBook risk,double tick) {
        double inventoryRisk=Math.min(2,Math.abs(risk.inventory()-risk.targetInventory())/(double)Math.max(1,risk.targetInventory()));
        // Volatility is per second. Quote risk covers the ~2s refresh horizon, not an entire price swing.
        double riskSpread=tick+m.lastPrice()*(m.volatility()*Math.sqrt(2)*.6
                +.0001*Math.max(0,e.volatilityMultiplier()-1)+.0001*inventoryRisk
                +.00005*Math.abs(m.imbalance())+.0001*Math.max(0,1/e.liquidityMultiplier()-1));
        double spread=riskSpread*Math.sqrt(e.spreadMultiplier());
        // Riskier markets also reduce size. Do not manufacture gaps comparable to the 6% dynamic VI.
        return clamp(spread,tick,Math.max(tick,m.lastPrice()*.004));
    }
    public List<Quote> quotes(PriceMetricService.Metrics m,MarketEnvironment e,RiskBook risk,double tick,double scale,
                               QuoteConstraints constraints) {
        return quotes(m,e,risk,tick,scale,constraints,m.referenceVwap());
    }

    public List<Quote> quotes(PriceMetricService.Metrics m,MarketEnvironment e,RiskBook risk,double tick,double scale,
                              QuoteConstraints constraints,double fundamental) {
        double reference=reservationPrice(m,risk,tick,fundamental),spread=halfSpread(m,e,risk,tick);
        boolean emergency=m.bidDepth()<4 || m.askDepth()<4;
        if(emergency) spread=Math.min(spread*1.4,Math.max(tick,m.lastPrice()*.005));
        double step=Math.max(tick,spread*.5);
        long lower=ceilToTick(constraints.lowerPrice()),upper=floorToTick(constraints.upperPrice());
        long bidRoom=lower,askRoom=upper;
        for(int level=0;level<3;level++) {bidRoom=stepUp(bidRoom,step); askRoom=stepDown(askRoom,step);}
        // Fit the entire ladder inside the legal band BEFORE sizing. Clipping individual quotes would
        // collapse four levels onto one price or discard an entire side at the band boundary.
        reference=bidRoom+spread<=askRoom-spread
                ? clamp(reference,bidRoom+spread,askRoom-spread) : (lower+upper)/2.0;
        long bidCeiling=constraints.bestExternalAsk()>0?Math.min(upper,previousTickPrice(constraints.bestExternalAsk())):upper;
        long askFloor=constraints.bestExternalBid()>0?Math.max(lower,nextTickPrice(constraints.bestExternalBid())):lower;
        long bid=Math.min(floorToTick(reference-spread),bidCeiling);
        long ask=Math.max(ceilToTick(reference+spread),askFloor);
        // Backstop competitive external interest instead of repeatedly stepping in front of it.
        // The bounded tolerance prevents a remote/stale quote from dragging our ladder away from trades.
        double tolerance=Math.max(tick,Math.min(spread*.5,m.midPrice()*.0015));
        long externalBid=constraints.bestExternalBid(),externalAsk=constraints.bestExternalAsk();
        if(externalBid>0 && externalBid>=bid-tolerance && externalBid<=bid
                && previousTickPrice(externalBid)>=bidRoom) bid=previousTickPrice(externalBid);
        if(externalAsk>0 && externalAsk<=ask+tolerance && externalAsk>=ask
                && nextTickPrice(externalAsk)<=askRoom) ask=nextTickPrice(externalAsk);
        List<Quote> quotes=new ArrayList<>(); long cash=risk.cashBudget(); int inventory=risk.inventory();
        int buyCapacity=Math.max(0,Math.min(risk.maxInventory()-inventory,(int)(risk.riskLimit()/Math.max(1,m.markPrice()))-inventory));
        for(int level=0;level<QUOTE_LEVELS;level++) {
            double riskSize=1/(1+m.volatility()*100+Math.max(0,e.volatilityMultiplier()-1)*.15);
            int size=emergency?EMERGENCY_QUOTE_SIZE:Math.max(1,(int)(18*scale*e.liquidityMultiplier()*riskSize/(1+level*.5)));
            int b=bid>=lower&&bid<=upper?Math.min(size,Math.min(buyCapacity,(int)Math.min(Integer.MAX_VALUE,cash/(bid*1.001)))):0;
            int a=ask>=lower&&ask<=upper?Math.min(size,inventory):0;
            if(b>0) {quotes.add(new Quote("BUY",bid,b)); cash-=Math.ceil(bid*b*1.001); buyCapacity-=b;}
            if(a>0) {quotes.add(new Quote("SELL",ask,a)); inventory-=a;}
            bid=stepDown(bid,step); ask=stepUp(ask,step);
        }
        return quotes;
    }
    private long stepDown(long price,double step) {return floorToTick(Math.min(previousTickPrice(price),price-step));}
    private long stepUp(long price,double step) {return ceilToTick(Math.max(nextTickPrice(price),price+step));}
}
