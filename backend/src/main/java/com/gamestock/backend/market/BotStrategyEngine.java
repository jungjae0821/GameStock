package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.BotProfile.*;
import static com.gamestock.backend.market.MarketEnvironment.clamp;

public final class BotStrategyEngine {
    public record Position(int quantity, long cash, double averagePrice, long ageMillis, double targetQuantity) {
        public Position(int quantity,long cash,double averagePrice,long ageMillis) {this(quantity,cash,averagePrice,ageMillis,0);}
    }
    public record Decision(String side, double score, double confidence, int quantity, boolean market, double limitPrice, long ttlMillis) {}
    private final Map<String, Double> estimates=new HashMap<>();
    private final Random random;
    private final long seed;
    public BotStrategyEngine(long seed){this.seed=seed; random=new Random(seed);}
    public static boolean hasInventoryDemand(BotProfile p) {
        return p.strategy().family()==Family.NOISE || p.strategy().family()==Family.AGGRESSIVE_LIQUIDITY_TAKER;
    }
    /** Private cash-backed allocation demand. Inventory is acquired only through normal paid fills. */
    public double targetQuantity(BotProfile p,String symbol,long now,long equity,int symbols,double price) {
        if(!hasInventoryDemand(p) || equity<=0) return 0;
        Random preference=new Random(seed^((long)(p.username()+":"+symbol).hashCode()*0x9E3779B97F4A7C15L));
        double weight=.75+preference.nextDouble()*.5;
        double period=120_000+preference.nextDouble()*240_000;
        double phase=preference.nextDouble()*Math.PI*2;
        double demand=1+.4*Math.sin((now%((long)period))/period*Math.PI*2+phase);
        if(p.holdingTimePreference()<5000) {
            // Fast flow participants have private short-lived allocation needs, not a common fair price.
            // The target is only an intent: every share must first be bought from a real counterparty.
            period=StockMarketProfile.isLargeCap(symbol) ? 30_000+preference.nextDouble()*90_000
                    : 5_000+preference.nextDouble()*15_000;
            double cycle=Math.sin((now%((long)period))/period*Math.PI*2+phase);
            return Math.min(equity/Math.max(1,price)*.1,Math.max(0,.8+1.4*cycle)*weight);
        }
        // Even at maximum demand/weight, aggregate targets consume less than 75% of account equity.
        double budget=equity*(.2+.25*p.riskTolerance())/Math.max(1,symbols);
        return clamp(budget*weight*demand/Math.max(1,price),0,p.positionLimit()*.2);
    }

    public double threshold(BotProfile p,PriceMetricService.Metrics m) {
        return p.confidenceThreshold()*(1+Math.max(0,m.volatility()*100-p.volatilityTolerance()));
    }
    public double estimate(BotProfile p,String symbol,double noisyObservation) {
        String key=p.username()+":"+symbol;
        // A bot's general optimism and its company-specific research error are distinct.
        // Stable per-symbol errors do not disappear by repeatedly averaging observations.
        double errorWeight=StockMarketProfile.isLargeCap(symbol)?StockMarketProfile.of(symbol).movementWeight():1;
        double researchError=new Random(seed ^ ((long)key.hashCode()*0x9E3779B97F4A7C15L)).nextGaussian()*.025;
        double target=noisyObservation*(1+(p.valueError()+researchError)*errorWeight);
        double old=estimates.getOrDefault(key,target);
        double estimate=old+p.updateSpeed()*(target-old);
        estimates.put(key,estimate); return estimate;
    }
    public double score(BotProfile p, PriceMetricService.Metrics m, MarketEnvironment e,
                        Position position,double estimatedValue,double perceivedNews,double noise) {
        double fast=m.return5s()*100, slow=m.return20s()*100, slope=m.emaSlope()*300;
        // A quiet minute does not erase longer-lived executed-price information.
        double reversionPrice=.4*m.vwap()+.6*m.referenceVwap();
        double vwapGap=(m.lastPrice()/Math.max(1,reversionPrice)-1)*100;
        double micro=(m.microPrice()/Math.max(1,m.midPrice())-1)*100;
        double value=(estimatedValue/Math.max(1,m.markPrice())-1)/p.entryThreshold();
        double signal=switch(p.strategy()) {
            case FAST_MOMENTUM -> .8*fast+.2*slow;
            case SLOW_MOMENTUM -> .5*slow+.2*slope+m.return60s()*20+m.return300s()*10;
            case BREAKOUT, BREAKOUT_SCALPER -> m.lastPrice()>=m.recentHigh() && slow>.01?slow+.3:
                    m.lastPrice()<=m.recentLow() && slow<-.01?slow-.3:0;
            case TREND_FOLLOW -> .4*slow+.3*slope+m.return60s()*20+m.return300s()*10;
            case PULLBACK -> .8*slow-.4*fast;
            case VOLUME_MOMENTUM -> slow*(1+Math.max(0,m.volumeTrend()));
            case ACCELERATION -> .4*fast+m.acceleration()*150;
            case RSI_FADE -> (50-m.rsi())/45;
            case ZSCORE -> -m.zscore()*.4;
            case VWAP_FADE -> -vwapGap;
            case EXHAUSTION -> -fast*Math.max(.1,-m.volumeTrend())-.2*m.zscore();
            case LIQUIDITY_FADE -> -.6*m.imbalance()-.4*vwapGap;
            case SPIKE_FADE -> -m.acceleration()*200-.3*fast;
            case DEEP_VALUE -> Math.abs(value)<1.5?0:value*.6;
            case AGGRESSIVE_VALUE -> value*1.3;
            case CONSERVATIVE_VALUE -> value*p.confidence()*.7;
            case CATALYST_VALUE -> value*.6+perceivedNews*p.newsSensitivity()*8;
            case SLOW_VALUE -> value*.5;
            case SCALPER -> micro*.6+m.imbalance()*.4;
            case BOOK_IMBALANCE -> m.imbalance();
            case MICRO_TREND -> micro*.5+fast*.5;
            case VWAP_SCALPER -> -.5*vwapGap+.3*m.imbalance();
            case SPREAD_TAKER -> micro*(m.spread()/Math.max(1,m.midPrice())<.003?1:.2);
            case LIQUIDITY_SNIPER -> m.imbalance()*(m.bidDepth()+m.askDepth()<80?1.5:.3);
            case NEWS_REACTOR -> perceivedNews*p.newsSensitivity()*25;
            case NEWS_SKEPTIC -> perceivedNews*p.newsSensitivity()*12-.5*vwapGap;
            case NOISE_FLOW -> noise*.25+e.directionBias()*.25;
            case NOISE_RANDOM -> noise*.5;
            case SWING_TREND -> m.return300s()*60+m.return60s()*40;
            case LIQUIDITY_TAKER -> .6*m.imbalance()+.4*fast+e.directionBias()*.4;
        };
        if (p.strategy().family()==Family.MOMENTUM) signal *= 1+e.trendStrength()*.3;
        if (p.strategy().family()==Family.CONTRARIAN) signal -= vwapGap*e.meanReversionStrength()*.2;
        if(p.strategy().family()==Family.VALUE && Math.abs(estimatedValue/m.markPrice()-1)<p.exitThreshold()) signal*=.2;
        double environmentWeight=switch(p.strategy().family()) {
            case NOISE, NEWS, AGGRESSIVE_LIQUIDITY_TAKER -> .25;
            case MOMENTUM, SWING -> .04;
            default -> .08;
        };
        signal += environmentWeight*(e.directionBias()+(e.buyPressure()-e.sellPressure())*.3);
        signal += p.noiseLevel()*noise+p.buyBias()-p.sellBias();
        if(hasInventoryDemand(p)) {
            if(p.holdingTimePreference()<5000)signal*=.15;
            signal += (p.holdingTimePreference()<5000?1.5:.9)*clamp((position.targetQuantity()-position.quantity())/Math.max(1,position.targetQuantity()),-1,1);
        }
        signal -= position.quantity()/(double)p.positionLimit()*p.inventoryAversion();
        if(position.quantity()>0 && position.averagePrice()>0) {
            double pnl=m.lastPrice()/position.averagePrice()-1;
            if(pnl<=-p.stopLoss() || pnl>=p.takeProfit()) signal-=1.5;
            if(position.ageMillis()>p.holdingTimePreference()) signal-=Math.min(.6,
                    (position.ageMillis()/(double)p.holdingTimePreference()-1)*.15);
        }
        return signal;
    }
    public Decision decide(BotProfile p,PriceMetricService.Metrics m,MarketEnvironment e,Position position,
                           double estimatedValue,double perceivedNews,double tick,double marketScale) {
        return decide(p,m,e,position,estimatedValue,perceivedNews,tick,marketScale,null,0);
    }

    public Decision decide(BotProfile p,PriceMetricService.Metrics m,MarketEnvironment e,Position position,
                           double estimatedValue,double perceivedNews,double tick,double marketScale,String symbol,long dayReference) {
        double score=score(p,m,e,position,estimatedValue,perceivedNews,random.nextGaussian());
        boolean large=StockMarketProfile.isLargeCap(symbol) && dayReference>0;
        double dailyAnchor=large?StockMarketProfile.valuationAnchor(symbol,dayReference,estimatedValue,perceivedNews):estimatedValue;
        if(large) {
            // A stock cannot become a penny stock just because hundreds of accounts trade it.
            // This changes willingness to trade; only actual matched orders change the price.
            score=score*.35-(m.lastPrice()/dailyAnchor-1)/(StockMarketProfile.normalDailyMove(symbol)*.4);
        } else if(StockMarketProfile.isSpeculative(symbol)) {
            score+=e.directionBias()*.45+clamp(m.return60s()*8,-.8,.8);
        }
        double threshold=threshold(p,m);
        String side=score>threshold?"BUY":score<-threshold?"SELL":"HOLD";
        double confidence=clamp(Math.abs(score)*p.confidence(),0,1);
        // Intraday traders can wait for a favourable spread even without a directional forecast.
        // This is a small conditional limit order, never a random reversal of a BUY signal.
        boolean passiveIntraday=side.equals("HOLD") && p.strategy().family()==Family.INTRADAY;
        if(passiveIntraday) {
            side=position.quantity()>0?"SELL":"BUY";
            confidence=.15+.2*p.riskTolerance();
        }
        boolean urgent=p.strategy().family()==Family.AGGRESSIVE_LIQUIDITY_TAKER;
        boolean patient=p.strategy().family()==Family.VALUE || p.strategy().family()==Family.SWING
                || p.strategy().family()==Family.CONTRARIAN;
        double marketChance=p.marketOrderProbability()*e.marketOrderMultiplier()*(.5+confidence)
                /(1+m.spread()/Math.max(1,m.midPrice())*100);
        if(patient) marketChance*=.25;
        boolean market= random.nextDouble()<clamp(urgent?marketChance+.4:marketChance,0,.9);
        double aggression=clamp(p.aggression()+confidence*.25+e.breakoutProbability()*.1-e.reversalProbability()*.1,0,1);
        double price;
        double draw=random.nextDouble();
        int distance=1+random.nextInt(4)+(int)(m.volatility()*100);
        double crossChance=aggression*(patient?.15:.65);
        // Pay the spread when the strategy's own expected exit covers round-trip fees and risk.
        // Use a capped aggressive LIMIT: a favourable estimate is not permission for unlimited slippage.
        boolean economicCross=executableEdge(p,m,side,estimatedValue)>0;
        boolean riskExit="SELL".equals(side) && position.quantity()>0 && position.averagePrice()>0
                && (m.bestBid()/position.averagePrice()-1<=-p.stopLoss()
                || (m.bestBid()/position.averagePrice()-1>=p.takeProfit()
                    && m.bestBid()/position.averagePrice()-1>2*.001));
        if(economicCross || riskExit) {crossChance=1;market=false;}
        boolean intradayMaker=p.strategy().family()==Family.INTRADAY && !economicCross && !riskExit;
        // Compete inside the spread with independently priced resting interest. Crossing still
        // respects ordinary price-time priority, regardless of whether the maker is an LP or human.
        double improvement=Math.min(Math.max(0,m.spread()-tick),tick*(1+random.nextInt(3)));
        if("BUY".equals(side)) price=draw<crossChance?m.bestAsk():draw<.8?m.bestBid()+improvement:
                draw<.9?m.bestBid():m.bestBid()-tick*distance;
        else price=draw<crossChance?m.bestBid():draw<.8?m.bestAsk()-improvement:
                draw<.9?m.bestAsk():m.bestAsk()+tick*distance;
        if(intradayMaker) {
            market=false;
            double reference=intradayReference(p,m);
            double edge=.00125+m.volatility()*Math.sqrt(4)*.5;
            price=side.equals("BUY")?Math.min(m.bestAsk()-tick,reference/(1+edge)):
                    Math.max(m.bestBid()+tick,Math.max(reference*(1+edge),passiveIntraday?position.averagePrice()*1.0025:0));
        }
        if(hasInventoryDemand(p) && p.holdingTimePreference()<5000) {
            // Cash-flow demand is price sensitive. An urgent allocation change is not permission
            // to chase an empty book arbitrarily far from recent paid transactions.
            double edge=StockMarketProfile.isSpeculative(symbol)?.015+p.aggression()*.02:.004+p.aggression()*.006;
            double reservation=(.8*m.vwap()+.2*m.microPrice())*(1+clamp(score,-1,1)*edge);
            if(market || random.nextDouble()<clamp(p.aggression()+confidence*.65,.3,.95))
                price=side.equals("BUY")?m.bestAsk():m.bestBid();
            market=false;
            price=side.equals("BUY")?Math.min(price,reservation):Math.max(price,reservation);
        }
        if(large) {
            // Large-cap demand fades before the safety band: avoid a pile-up at a hard +/-3% cap.
            double room=StockMarketProfile.normalDailyMove(symbol);
            price=side.equals("BUY")?Math.min(price,dailyAnchor*(1+room)):Math.max(price,dailyAnchor*(1-room));
            market=false;
        }
        price=Math.max(tick,price);
        double volatilityPenalty=1+m.volatility()*150+Math.max(0,e.volatilityMultiplier()-1)*.25;
        double liquidity=clamp((m.bidDepth()+m.askDepth())/150.0,.2,1);
        int quantity=(int)Math.floor(p.maxOrderSize()*confidence*p.riskTolerance()*marketScale
                *Math.sqrt(e.volumeMultiplier())*liquidity/volatilityPenalty);
        quantity=Math.min(p.maxOrderSize(),Math.max(p.minOrderSize(),quantity));
        if(intradayMaker) quantity=Math.min(quantity,2);
        if(hasInventoryDemand(p) && position.targetQuantity()>0)
            quantity=Math.min(quantity,Math.max(1,(int)Math.ceil(Math.abs(position.targetQuantity()-position.quantity()))));
        if("BUY".equals(side)) quantity=Math.min(quantity,Math.min(p.positionLimit()-position.quantity(),
                (int)Math.min(Integer.MAX_VALUE,position.cash()/Math.max(1,(market?m.bestAsk()*1.03:price)*1.001))));
        else if("SELL".equals(side)) quantity=Math.min(quantity,position.quantity());
        else quantity=0;
        return new Decision(side,score,confidence,Math.max(0,quantity),market,price,
                p.decisionInterval()<1000?Math.max(1200,p.ttlMillis(random)):p.ttlMillis(random));
    }

    double executableEdge(BotProfile p,PriceMetricService.Metrics m,String side,double estimatedValue) {
        if(!side.equals("BUY") && !side.equals("SELL")) return Double.NEGATIVE_INFINITY;
        double target=switch(p.strategy().family()) {
            case VALUE -> estimatedValue;
            case CONTRARIAN -> .4*m.vwap()+.6*m.referenceVwap();
            case SWING -> m.lastPrice()*(1+clamp(m.return300s()*.5+m.return60s()*.25,-.03,.03));
            case INTRADAY -> intradayReference(p,m);
            default -> 0;
        };
        if(target<=0 || (side.equals("BUY")?m.askDepth():m.bidDepth())==0) return Double.NEGATIVE_INFINITY;
        double execution=side.equals("BUY")?m.bestAsk():m.bestBid();
        double edge=side.equals("BUY")?target/Math.max(1,execution)-1:execution/Math.max(1,target)-1;
        double required=2*.001+m.volatility()*Math.sqrt(Math.min(60,p.holdingTimePreference()/1000.0))
                + (p.strategy().family()==Family.VALUE?Math.max(p.exitThreshold(),p.entryThreshold()*.5):.001);
        return edge-required;
    }

    private double intradayReference(BotProfile p,PriceMetricService.Metrics m) {
        double reference=switch(p.strategy()) {
            case VWAP_SCALPER -> .7*m.vwap()+.3*m.microPrice();
            case BOOK_IMBALANCE -> m.microPrice()*(1+m.imbalance()*.0005);
            case MICRO_TREND, BREAKOUT_SCALPER -> m.microPrice()*(1+clamp(m.return5s()*.5+m.return20s()*.2,-.005,.005));
            default -> .5*m.vwap()+.5*m.microPrice();
        };
        return reference*(1+(p.buyBias()-p.sellBias())*.002);
    }
}
