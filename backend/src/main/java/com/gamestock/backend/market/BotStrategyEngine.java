package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.BotProfile.*;
import static com.gamestock.backend.market.MarketEnvironment.clamp;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

public final class BotStrategyEngine {
    /** Do not turn a small inventory error into an immediate buy/sell reversal. */
    static final double INVENTORY_DEADBAND_RATIO = .15;

    public record Position(int quantity, long cash, double averagePrice, long ageMillis, double targetQuantity) {
        public Position(int quantity,long cash,double averagePrice,long ageMillis) {this(quantity,cash,averagePrice,ageMillis,0);}
    }
    public record Decision(String side, double score, double confidence, int quantity, boolean market, double limitPrice, long ttlMillis) {}
    private final Map<String, Double> estimates=new HashMap<>();
    private final Random random;
    private final long seed;
    private final BotAllocationDemand allocation;
    public BotStrategyEngine(long seed){this.seed=seed; random=new Random(seed);allocation=new BotAllocationDemand(seed);}

    public static boolean hasInventoryDemand(BotProfile p) {
        return BotPositionRules.hasInventoryDemand(p);
    }
    /** Private cash-backed allocation demand. Inventory is acquired only through normal paid fills. */
    public double targetQuantity(BotProfile p,String symbol,long now,long equity,int symbols,double price) {
        return allocation.targetQuantity(p,symbol,now,equity,symbols,price);
    }

    public double threshold(BotProfile p,PriceMetricService.Metrics m) {
        return p.confidenceThreshold()*(1+Math.max(0,m.volatility()*100-p.volatilityTolerance()));
    }
    /** Each family researches a different horizon; a common stochastic value is only one input. */
    static double valuationObservation(BotProfile p,PriceMetricService.Metrics m,double fundamental,double consensus) {
        return BotStrategySignals.valuationObservation(p,m,fundamental,consensus);
    }
    /** Liquid short-horizon strategies price execution risk rather than repeatedly reset to value. */
    static double valuationObservation(BotProfile p,PriceMetricService.Metrics m,double fundamental,double consensus,String symbol) {
        return BotStrategySignals.valuationObservation(p,m,fundamental,consensus,symbol);
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
        double signal=BotStrategySignals.strategySignal(p,m,e,estimatedValue,perceivedNews,noise);
        double environmentWeight=switch(p.strategy().family()) {
            case NOISE, NEWS, AGGRESSIVE_LIQUIDITY_TAKER -> .25;
            case MOMENTUM, SWING -> .04;
            default -> .08;
        };
        signal += environmentWeight*(e.directionBias()+(e.buyPressure()-e.sellPressure())*.3);
        signal += p.noiseLevel()*noise+p.buyBias()-p.sellBias();
        double targetGap=hasInventoryDemand(p) && position.targetQuantity()<=0?
                -position.quantity():effectiveInventoryGap(position);
        if(hasInventoryDemand(p)) {
            if(p.holdingTimePreference()<5000)signal*=.15;
            signal += (p.holdingTimePreference()<5000?1.5:.9)*clamp(targetGap/Math.max(1,position.targetQuantity()),-1,1);
        }
        // Holding the desired inventory is neutral; only excess inventory encourages selling.
        double excess=BotPositionRules.inventoryExcess(p,position);
        signal -= excess*p.inventoryAversion();
        if(position.quantity()>0 && position.averagePrice()>0) {
            double pnl=m.lastPrice()/position.averagePrice()-1;
            if(pnl<=-p.stopLoss()) signal-=1.5;
            // Let profitable trends run. Taking profit is conditional on a weakening thesis.
            else if(pnl>=p.takeProfit() && signal<=threshold(p,m)) signal-=.6;
            if(excess>0 && position.ageMillis()>p.holdingTimePreference()) signal-=Math.min(.3,
                    (position.ageMillis()/(double)p.holdingTimePreference()-1)*.05)*Math.min(1,excess);
        }
        return signal;
    }
    public Decision decide(BotProfile p,PriceMetricService.Metrics m,MarketEnvironment e,Position position,
                           double estimatedValue,double perceivedNews,double tick,double marketScale) {
        return decide(p,m,e,position,estimatedValue,perceivedNews,tick,marketScale,null,0);
    }

    public Decision decide(BotProfile p,PriceMetricService.Metrics m,MarketEnvironment e,Position position,
                           double estimatedValue,double perceivedNews,double tick,double marketScale,String symbol,long dayReference) {
        if(dayReference>0 && StockMarketProfile.isSpeculative(symbol)) {
            // The news fundamental ranges from 0.5x to 4x the listing anchor, often outside today's
            // +/-20% bot band. An unreachable value puts every offer above the ceiling (or every bid
            // below the floor), so one side stacks a wall there while the fade removes the other.
            // Research is only actionable up to the band itself.
            estimatedValue=clamp(estimatedValue,dayReference*(1-PriceLimitPolicy.BOT_DAILY_LIMIT_RATE),
                    dayReference*(1+PriceLimitPolicy.BOT_DAILY_LIMIT_RATE));
        }
        double score=score(p,m,e,position,estimatedValue,perceivedNews,random.nextGaussian());
        boolean flow=hasInventoryDemand(p);
        boolean large=StockMarketProfile.isLargeCap(symbol) && dayReference>0;
        // The estimate already carries the moving market consensus. Re-anchoring large caps to
        // yesterday's close here held them in a fixed box around that close all day.
        double dailyAnchor=estimatedValue;
        if(large && !flow) {
            // A stock cannot become a penny stock just because hundreds of accounts trade it.
            // This changes willingness to trade; only actual matched orders change the price.
            score=score*.35-(m.lastPrice()/dailyAnchor-1)/(StockMarketProfile.normalDailyMove(symbol)*.4);
        } else if(StockMarketProfile.isSpeculative(symbol) && !flow) {
            // Independent valuation survives the day-reference rollover. Both expensive and
            // cheap prices meet opposing demand instead of momentum walking to a daily wall.
            score=clamp(score,-2,2)*.6+e.directionBias()*.2+clamp(m.return60s()*4,-.4,.4)
                    +clamp(Math.log(Math.max(1,estimatedValue)/Math.max(1,m.markPrice()))/.05,-4,4);
        }
        if(dayReference>0 && StockMarketProfile.isSpeculative(symbol) && !flow) {
            // Fade pursuit only in the last two points before the +/-20% bot safety wall, so funded
            // bids do not stack at the wall. Starting at 15% parked every hot symbol near +17%.
            double move=m.markPrice()/dayReference-1;
            score-=Math.signum(move)*6*clamp((Math.abs(move)-.18)/.02,0,1);
        }
        double dipStrength=BotStrategySignals.dipStrength(m,symbol,dayReference);
        boolean capitulationFloor=dipStrength>0 && m.bidDepth()<=0;
        boolean protectiveExit=BotStrategySignals.protectiveExit(p,m,position) && !capitulationFloor;
        boolean stopLossExit=protectiveExit && m.bestBid()/position.averagePrice()-1<=-p.stopLoss();
        if(dipStrength>0) {
            // A deep drawdown should attract some cash-backed demand instead of
            // letting every trend signal become another sell order. The amount
            // is bounded and still requires a real ask to trade against.
            score+=dipStrength*(.55+.45*p.riskTolerance());
        }
        double threshold=threshold(p,m);
        boolean dipHold=position.quantity()>0 && dipStrength>=.65 && !protectiveExit && score<0;
        if(dipHold) {
            // Hold recently acquired inventory through a weak trend signal at
            // the lower band. Stop-loss and take-profit exits remain eligible.
            score=Math.max(score,-threshold*.5);
        }
        // A directional thesis must not indefinitely lock legacy holdings away from the book.
        // Trim only excess inventory after this trader's own holding horizon, through paid fills.
        int excessQuantity=Math.max(0,position.quantity()-(int)Math.ceil(position.targetQuantity()+inventoryDeadband(position)));
        boolean inventoryExit=excessQuantity>0 && position.ageMillis()>=p.holdingTimePreference()
                && m.bidDepth()>0 && m.askDepth()<=4 && !capitulationFloor && !stopLossExit;
        if(inventoryExit)score=Math.min(score,-threshold*2);
        String side=score>threshold?"BUY":score<-threshold?"SELL":"HOLD";
        // Allocation-driven traders stop once their allocation is satisfied. Previously a fresh
        // random score could buy or sell at the target, then immediately trade back to repair the
        // inventory error it had just created, repeatedly sweeping both ends of the same ladder.
        if(flow && !protectiveExit && !inventoryExit) {
            double gap=position.targetQuantity()<=0?-position.quantity():effectiveInventoryGap(position);
            if(("BUY".equals(side) && gap<=0) || ("SELL".equals(side) && gap>=0))side="HOLD";
        }
        double confidence=clamp(Math.abs(score)*p.confidence(),0,1);
        boolean dipBuyer=false;
        if(position.quantity()==0 && !side.equals("BUY") && dipStrength>0 && m.askDepth()>0) {
            // Counter-cyclical demand is probabilistic so strategy diversity is
            // retained, but becomes meaningful near the bot price-band floor.
            double probability=clamp(.15+dipStrength*(.45+.25*p.riskTolerance()),0,.75);
            if(random.nextDouble()<probability) {
                dipBuyer=true;
                score=Math.max(score,threshold+.05+.15*dipStrength);
                side="BUY";
                confidence=Math.max(confidence,.25+.35*dipStrength);
            }
        }
        // Intraday traders can wait for a favourable spread even without a directional forecast.
        // This is a small conditional limit order, never a random reversal of a BUY signal.
        boolean passiveIntraday=side.equals("HOLD") && p.strategy().family()==Family.INTRADAY && !dipHold;
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
        if(dipBuyer) {
            // Lift a real ask with a capped limit often enough to let a rebound
            // appear in the tape; this never bypasses the normal price band.
            market=false;
            crossChance=Math.max(crossChance,.55+.20*dipStrength);
        }
        // Pay the spread when the strategy's own expected exit covers round-trip fees and risk.
        // Use a capped aggressive LIMIT: a favourable estimate is not permission for unlimited slippage.
        boolean economicCross=executableEdge(p,m,side,estimatedValue)>0;
        boolean riskExit="SELL".equals(side) && protectiveExit;
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
            price=BotOrderPricing.intradayMakerPrice(p,m,side,tick,position,passiveIntraday);
        }
        if(flow && !riskExit && !inventoryExit) {
            double reservation=BotOrderPricing.flowReservation(p,m,side,score,estimatedValue,tick,symbol,large);
            if(market || random.nextDouble()<clamp(p.aggression()+confidence*.65,.3,.95))
                price=side.equals("BUY")?m.bestAsk():m.bestBid();
            market=false;
            price=side.equals("BUY")?Math.min(price,reservation):Math.max(price,reservation);
        }
        if(large && !flow && !inventoryExit && !riskExit) {
            // Large-cap demand fades before the safety band: avoid a pile-up at a hard +/-3% cap.
            double room=StockMarketProfile.normalDailyMove(symbol);
            price=side.equals("BUY")?Math.min(price,dailyAnchor*(1+room)):Math.max(price,dailyAnchor*(1-room));
            market=false;
        } else if(StockMarketProfile.isSpeculative(symbol) && !flow && !inventoryExit && !riskExit) {
            // A price-sensitive limit follows each participant's research, not today's
            // lower/upper wall. Neither momentum nor a random market order can ignore it.
            double room=.04+.02*p.riskTolerance();
            price=side.equals("BUY")?Math.min(price,estimatedValue*(1+room)):
                    Math.max(price,estimatedValue*(1-room));
            market=false;
        }
        if(inventoryExit) {
            // An allocation reduction is not a bet that the old fundamental will be reached.
            // Offer near paid prices; take an existing bid only within a bounded execution cost.
            double reference=.8*m.vwap()+.2*m.microPrice();
            price=Math.max(m.bestBid(),reference*.995);
            market=false;
        }
        // Passive participants receive a stable, per-bot price-level preference. This keeps
        // every funded bot from collapsing onto the same best tick while preserving urgent
        // exits and executable value edges.
        if(dayReference>0 && !flow && !market && !economicCross && !riskExit && !inventoryExit && !dipBuyer
                && !side.equals("HOLD")
                && (side.equals("BUY")?price<m.bestAsk():price>m.bestBid())) {
            price=BotOrderPricing.passiveLevel(p,m,side,price,tick,symbol,dayReference,perceivedNews,seed);
        }
        price=Math.max(tick,price);
        int quantity=BotPositionRules.orderQuantity(p,m,e,position,side,confidence,marketScale,market,price,
                intradayMaker,flow,riskExit,inventoryExit,excessQuantity);
        return new Decision(side,score,confidence,Math.max(0,quantity),market,price,
                p.decisionInterval()<1000?Math.max(1200,p.ttlMillis(random)):p.ttlMillis(random));
    }

    double executableEdge(BotProfile p,PriceMetricService.Metrics m,String side,double estimatedValue) {
        return BotStrategySignals.executableEdge(p,m,side,estimatedValue);
    }

    static double inventoryDeadband(Position position) {
        return BotPositionRules.inventoryDeadband(position);
    }

    static double effectiveInventoryGap(Position position) {
        return BotPositionRules.effectiveInventoryGap(position);
    }
}
