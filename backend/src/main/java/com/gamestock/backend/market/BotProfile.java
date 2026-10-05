package com.gamestock.backend.market;

import java.util.*;

public record BotProfile(String username, String nickname, Strategy strategy,
        double riskTolerance, double aggression, double confidenceThreshold, double marketOrderProbability,
        int minOrderSize, int maxOrderSize, long holdingTimePreference, int positionLimit,
        double stopLoss, double takeProfit, long decisionInterval, long reactionLatency,
        double newsSensitivity, double volatilityTolerance, double inventoryAversion,
        double buyBias, double sellBias, double noiseLevel, double cancelSpeed,
        double valueError, double confidence, double entryThreshold, double exitThreshold, double updateSpeed) {
    public enum Family { MOMENTUM, CONTRARIAN, VALUE, INTRADAY, NEWS, NOISE, SWING, AGGRESSIVE_LIQUIDITY_TAKER }
    public enum Strategy {
        FAST_MOMENTUM(Family.MOMENTUM), SLOW_MOMENTUM(Family.MOMENTUM), BREAKOUT(Family.MOMENTUM), TREND_FOLLOW(Family.MOMENTUM), PULLBACK(Family.MOMENTUM), VOLUME_MOMENTUM(Family.MOMENTUM), ACCELERATION(Family.MOMENTUM),
        RSI_FADE(Family.CONTRARIAN), ZSCORE(Family.CONTRARIAN), VWAP_FADE(Family.CONTRARIAN), EXHAUSTION(Family.CONTRARIAN), LIQUIDITY_FADE(Family.CONTRARIAN), SPIKE_FADE(Family.CONTRARIAN),
        DEEP_VALUE(Family.VALUE), AGGRESSIVE_VALUE(Family.VALUE), CONSERVATIVE_VALUE(Family.VALUE), CATALYST_VALUE(Family.VALUE), SLOW_VALUE(Family.VALUE),
        SCALPER(Family.INTRADAY), BOOK_IMBALANCE(Family.INTRADAY), MICRO_TREND(Family.INTRADAY), VWAP_SCALPER(Family.INTRADAY), BREAKOUT_SCALPER(Family.INTRADAY), SPREAD_TAKER(Family.INTRADAY), LIQUIDITY_SNIPER(Family.INTRADAY),
        NEWS_REACTOR(Family.NEWS), NEWS_SKEPTIC(Family.NEWS), NOISE_FLOW(Family.NOISE), NOISE_RANDOM(Family.NOISE), SWING_TREND(Family.SWING), LIQUIDITY_TAKER(Family.AGGRESSIVE_LIQUIDITY_TAKER);
        private final Family family;
        Strategy(Family f) {family=f;}
        public Family family(){return family;}
    }
    public static List<BotProfile> defaults(long seed) {
        return population(seed,20);
    }
    public static List<BotProfile> activePopulation(long seed,int count) {
        if(count<20||count>10000)throw new IllegalArgumentException("Active population must be between 20 and 10000");
        List<BotProfile> result=new ArrayList<>(defaults(seed));Random random=new Random(seed^101);
        // Seventeen slots spread every family across the fifteen symbols instead of
        // adding thousands of identical fast-flow accounts and only three value investors.
        Strategy[] flow={Strategy.CONSERVATIVE_VALUE,Strategy.VWAP_FADE,Strategy.SCALPER,Strategy.NOISE_FLOW,
                Strategy.FAST_MOMENTUM,Strategy.DEEP_VALUE,Strategy.BOOK_IMBALANCE,Strategy.ZSCORE,
                Strategy.NEWS_REACTOR,Strategy.SWING_TREND,Strategy.AGGRESSIVE_VALUE,Strategy.NOISE_RANDOM,
                Strategy.MICRO_TREND,Strategy.RSI_FADE,Strategy.CATALYST_VALUE,Strategy.TREND_FOLLOW,Strategy.LIQUIDITY_TAKER};
        for(int i=20;i<count;i++)result.add(create(String.format("trader_bot_%02d",i+1),flow[(i-20)%flow.length],random).atSpeed(.05).microLots());
        return List.copyOf(result);
    }
    private BotProfile microLots() {
        return new BotProfile(username,nickname,strategy,riskTolerance,aggression,confidenceThreshold,marketOrderProbability,
                1,2,holdingTimePreference,strategy.family()==Family.VALUE || strategy.family()==Family.SWING ? 12 : 2,
                stopLoss,takeProfit,decisionInterval,reactionLatency,
                newsSensitivity,volatilityTolerance,inventoryAversion,buyBias,sellBias,noiseLevel,cancelSpeed,
                valueError,confidence,entryThreshold,exitThreshold,updateSpeed);
    }
    public static List<BotProfile> population(long seed,int count) {
        if(count<1 || count>5000) throw new IllegalArgumentException("Bot population must be between 1 and 5000");
        // Stable account IDs preserve existing balances. Names now describe the actual strategy.
        Strategy[] distribution={Strategy.FAST_MOMENTUM,Strategy.RSI_FADE,Strategy.DEEP_VALUE,Strategy.SCALPER,
                Strategy.SLOW_MOMENTUM,Strategy.ZSCORE,Strategy.CONSERVATIVE_VALUE,Strategy.BOOK_IMBALANCE,
                Strategy.NEWS_REACTOR,Strategy.VWAP_FADE,Strategy.CATALYST_VALUE,Strategy.MICRO_TREND,
                Strategy.BREAKOUT,Strategy.NOISE_FLOW,Strategy.SWING_TREND,Strategy.LIQUIDITY_TAKER,
                Strategy.TREND_FOLLOW,Strategy.NEWS_SKEPTIC,Strategy.NOISE_RANDOM,Strategy.VWAP_SCALPER};
        List<BotProfile> profiles=new ArrayList<>(); Random r=new Random(seed);
        for(int i=0;i<count;i++) {
            Strategy base=distribution[i%distribution.length];
            List<Strategy> family=Arrays.stream(Strategy.values()).filter(s->s.family()==base.family()).toList();
            Strategy strategy=i<distribution.length?base:family.get((i/distribution.length+i)%family.size());
            profiles.add(create(String.format("trader_bot_%02d",i+1),strategy,r));
        }
        return List.copyOf(profiles);
    }
    public BotProfile atSpeed(double timeScale) {
        if(timeScale<=0 || timeScale>1) throw new IllegalArgumentException("Bot time scale must be in (0,1]");
        return new BotProfile(username,nickname,strategy,riskTolerance,aggression,confidenceThreshold,marketOrderProbability,
                minOrderSize,maxOrderSize,Math.max(250,(long)(holdingTimePreference*timeScale)),positionLimit,
                stopLoss,takeProfit,Math.max(25,(long)(decisionInterval*timeScale)),Math.max(5,(long)(reactionLatency*timeScale)),
                newsSensitivity,volatilityTolerance,inventoryAversion,buyBias,sellBias,noiseLevel,cancelSpeed/timeScale,
                valueError,confidence,entryThreshold,exitThreshold,updateSpeed);
    }
    public static BotProfile create(String id,Strategy s,Random r) {
        long interval=s==Strategy.SCALPER?2000:s.family()==Family.INTRADAY?4000:s.family()==Family.VALUE?30000:
                s.family()==Family.SWING?60000:s.family()==Family.NEWS?2000:7000;
        double aggression=.15+r.nextDouble()*.75;
        return new BotProfile(id,s.name()+" "+id.substring(id.lastIndexOf('_')+1),s,.25+r.nextDouble()*.65,aggression,
                .10+r.nextDouble()*.18,.05+aggression*.5,1,15+r.nextInt(36),
                s.family()==Family.SWING?600_000:30_000+r.nextInt(150_000),50+r.nextInt(151),
                .015+r.nextDouble()*.045,.02+r.nextDouble()*.08,(long)(interval*(.75+r.nextDouble()*.5)),
                s==Strategy.SCALPER?100+r.nextInt(500):s.family()==Family.INTRADAY?200+r.nextInt(1000):
                        s.family()==Family.NEWS?500+r.nextInt(2000):500+r.nextInt(3000),
                .4+r.nextDouble(),.5+r.nextDouble(),.4+r.nextDouble()*.8,
                r.nextDouble()*.08,r.nextDouble()*.08,.03+r.nextDouble()*.12,.7+r.nextDouble()*.6,
                r.nextGaussian()*.025,.4+r.nextDouble()*.5,.003+r.nextDouble()*.018,.002+r.nextDouble()*.006,
                s==Strategy.SLOW_VALUE?.03:s==Strategy.AGGRESSIVE_VALUE?.5:.08+r.nextDouble()*.2);
    }
    public long ttlMillis(Random r) {
        long min=10_000,max=40_000;
        if(strategy==Strategy.SCALPER){min=2000;max=8000;}
        else if(strategy.family()==Family.INTRADAY){min=5000;max=20000;}
        else if(strategy.family()==Family.VALUE || strategy.family()==Family.SWING){min=30000;max=180000;}
        return (long)((min+r.nextDouble()*(max-min))/cancelSpeed);
    }
}
