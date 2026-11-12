package com.gamestock.backend.market;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static com.gamestock.backend.market.MarketEnvironment.clamp;

/**
 * Private cash-backed allocation targets split out of {@link BotStrategyEngine}. Owns the
 * remembered mean-reverting demand shocks and their own seeded Random, which is separate
 * from the engine's decision Random, so the draw sequence of either stream is unchanged.
 */
final class BotAllocationDemand {
    private static final class Demand {double x;long at;Demand(double x,long at){this.x=x;this.at=at;}}
    private final Map<String,Demand> demands=new HashMap<>();
    private final Random demandRandom;
    private final long seed;

    BotAllocationDemand(long seed) {
        this.seed=seed;
        demandRandom=new Random(seed^0x5DEECE66DL);
    }

    /** Mean-reverting unit noise remembered for memoryMillis; bounded to [-1,1]. */
    private double demandShock(String key,long now,double memoryMillis,Random preference) {
        Demand d=demands.computeIfAbsent(key,k->new Demand(preference.nextGaussian()*.6,now));
        double dt=Math.max(0,now-d.at);d.at=now;
        if(dt>0) {
            double decay=Math.exp(-dt/memoryMillis);
            d.x=d.x*decay+Math.sqrt(1-decay*decay)*.6*demandRandom.nextGaussian();
        }
        return clamp(d.x,-1,1);
    }

    /** Private cash-backed allocation demand. Inventory is acquired only through normal paid fills. */
    double targetQuantity(BotProfile p,String symbol,long now,long equity,int symbols,double price) {
        if(equity<=0) return 0;
        Random preference=new Random(seed^((long)(p.username()+":"+symbol).hashCode()*0x9E3779B97F4A7C15L));
        double weight=.75+preference.nextDouble()*.5;
        String key=p.username()+":"+symbol;
        // Allocation needs wander without a period. Fixed sine cycles made each trader buy and
        // sell on the same cadence, which repeated the same price swing between the same levels.
        if(BotStrategyEngine.hasInventoryDemand(p) && p.holdingTimePreference()<5000) {
            // Fast flow participants have private short-lived allocation needs, not a common fair price.
            // The target is only an intent: every share must first be bought from a real counterparty.
            double memory=StockMarketProfile.isLargeCap(symbol) ? 8_000+preference.nextDouble()*22_000
                    : 1_500+preference.nextDouble()*4_000;
            double cycle=demandShock(key,now,memory,preference);
            return Math.min(equity/Math.max(1,price)*.1/Math.max(1,symbols),Math.max(0,5+4*cycle)*weight);
        }
        double demand=1+.4*demandShock(key,now,30_000+preference.nextDouble()*60_000,preference);
        // Even at maximum demand/weight, aggregate targets consume less than 75% of account equity.
        double budget=equity*(.2+.25*p.riskTolerance())/Math.max(1,symbols);
        return Math.min(budget/Math.max(1,price),p.positionLimit()*.35)*weight*demand;
    }
}
