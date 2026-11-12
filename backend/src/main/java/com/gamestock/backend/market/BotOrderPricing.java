package com.gamestock.backend.market;

import static com.gamestock.backend.market.BotProfile.*;
import static com.gamestock.backend.market.MarketEnvironment.clamp;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

/**
 * Deterministic limit-price rules split out of {@link BotStrategyEngine#decide}: the
 * intraday maker quote, the flow trader's reservation price and the stable per-bot
 * passive price-level preference. No random draws happen here; the engine keeps the
 * Random calls that choose between these prices, in their original order.
 */
final class BotOrderPricing {
    private BotOrderPricing() {}

    /** Resting quote for an intraday trader waiting for a favourable spread. */
    static double intradayMakerPrice(BotProfile p,PriceMetricService.Metrics m,String side,double tick,
                                     BotStrategyEngine.Position position,boolean passiveIntraday) {
        double reference=BotStrategySignals.intradayReference(p,m);
        double edge=.00125+m.volatility()*Math.sqrt(4)*.5;
        return side.equals("BUY")?Math.min(m.bestAsk()-tick,reference/(1+edge)):
                Math.max(m.bestBid()+tick,Math.max(reference*(1+edge),passiveIntraday?position.averagePrice()*1.0025:0));
    }

    /** Worst price a cash-flow trader accepts for its allocation change. */
    static double flowReservation(BotProfile p,PriceMetricService.Metrics m,String side,double score,
                                  double estimatedValue,double tick,String symbol,boolean large) {
        // Cash-flow demand is price sensitive. An urgent allocation change is not permission
        // to chase an empty book arbitrarily far from recent paid transactions.
        double edge=StockMarketProfile.isSpeculative(symbol)?.015+p.aggression()*.02:.004+p.aggression()*.006;
        // A small allocation adjustment should not sweep 0.4–1% across an unchanged book.
        // Its execution budget follows short-horizon paid prices and non-bounce volatility.
        double executionRoom=Math.max(2*tick,Math.min(m.spread()*.5,3*tick));
        edge=Math.min(edge,Math.max(executionRoom/Math.max(1,m.executionVwap()),m.volatility()*Math.sqrt(5)));
        double reference=m.executionVwap();
        // Research nudges a flow trader's execution budget without dictating its allocation side.
        // Bound stale research locally so both acquisition and disposal remain possible.
        double researchRoom=large?.005:.05;
        double researched=clamp(estimatedValue,reference*(1-researchRoom),reference*(1+researchRoom));
        // Research may support urgent execution, but cannot turn an allocation reduction
        // into a bet on an unreachable future ask (or prevent replenishing a cash-backed need).
        researched=side.equals("BUY")?Math.max(reference,researched):Math.min(reference,researched);
        reference=.5*reference+.5*researched;
        return reference*(1+clamp(score,-1,1)*edge);
    }

    /**
     * Passive participants receive a stable, per-bot price-level preference. This keeps
     * every funded bot from collapsing onto the same best tick.
     */
    static double passiveLevel(BotProfile p,PriceMetricService.Metrics m,String side,double price,double tick,
                               String symbol,long dayReference,double perceivedNews,long seed) {
        int span=p.strategy().family()==Family.INTRADAY?1:2;
        int offset=Math.floorMod(Long.hashCode(seed^((long)(p.username()+":"+symbol+":"+side).hashCode()*0x9E3779B97F4A7C15L)),span*2+1)-span;
        long dispersed=Math.round(price+offset*tick);
        long bounded=quoteBand(symbol,dayReference,perceivedNews).clamp(dispersed);
        price=side.equals("BUY")?floorToTick(bounded):ceilToTick(bounded);
        // A passive price preference must not turn into an aggressive order or sweep
        // extra levels. Orders already chosen to cross keep their original execution cap.
        return side.equals("BUY")?Math.min(price,previousTickPrice(Math.round(m.bestAsk()))):
                Math.max(price,nextTickPrice(Math.round(m.bestBid())));
    }
}
