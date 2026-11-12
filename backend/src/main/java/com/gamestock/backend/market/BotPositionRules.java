package com.gamestock.backend.market;

import static com.gamestock.backend.market.BotProfile.*;
import static com.gamestock.backend.market.MarketEnvironment.clamp;

/**
 * Inventory and order-size rules split out of {@link BotStrategyEngine}: the deadband
 * around a bot's target inventory, how far the holding is from that target, and how
 * many shares one decision may buy or sell given cash, limits and need. Pure functions;
 * {@code BotStrategyEngine} keeps delegating entry points for its existing callers.
 */
final class BotPositionRules {
    private BotPositionRules() {}

    static boolean hasInventoryDemand(BotProfile p) {
        return p.strategy().family()==Family.NOISE || p.strategy().family()==Family.AGGRESSIVE_LIQUIDITY_TAKER;
    }

    static double inventoryDeadband(BotStrategyEngine.Position position) {
        return position.targetQuantity()>0
                ? Math.max(1,Math.ceil(position.targetQuantity()*BotStrategyEngine.INVENTORY_DEADBAND_RATIO))
                : 0;
    }

    static double effectiveInventoryGap(BotStrategyEngine.Position position) {
        double gap=position.targetQuantity()-position.quantity(),deadband=inventoryDeadband(position);
        if(deadband<=0 || Math.abs(gap)<=deadband)return 0;
        // Preserve the full cash-backed shortfall signal. The deadband is primarily
        // for preventing an excess inventory from flipping straight into a sale;
        // otherwise an empty flow bot could lose its initial buy pressure.
        return gap>0?gap:gap+deadband;
    }

    /** Excess inventory relative to the position limit. Holding the desired inventory is neutral. */
    static double inventoryExcess(BotProfile p,BotStrategyEngine.Position position) {
        double delta=position.quantity()-position.targetQuantity(),deadband=inventoryDeadband(position);
        double effectiveExcess;
        if(position.targetQuantity()<=0)effectiveExcess=delta;
        else if(delta>0)effectiveExcess=Math.max(0,delta-deadband);
        else if(Math.abs(delta)<=deadband)effectiveExcess=0;
        else effectiveExcess=delta;
        return effectiveExcess/Math.max(1.0,p.positionLimit());
    }

    /** Order size from confidence and liquidity, capped by cash, position limit and need. */
    static int orderQuantity(BotProfile p,PriceMetricService.Metrics m,MarketEnvironment e,
                             BotStrategyEngine.Position position,String side,double confidence,double marketScale,
                             boolean market,double price,boolean intradayMaker,boolean flow,boolean riskExit,
                             boolean inventoryExit,int excessQuantity) {
        double volatilityPenalty=1+m.volatility()*150+Math.max(0,e.volatilityMultiplier()-1)*.25;
        double liquidity=clamp((m.bidDepth()+m.askDepth())/150.0,.2,1);
        int quantity=(int)Math.floor(p.maxOrderSize()*confidence*p.riskTolerance()*marketScale
                *Math.sqrt(e.volumeMultiplier())*liquidity/volatilityPenalty);
        quantity=Math.min(p.maxOrderSize(),Math.max(p.minOrderSize(),quantity));
        if(intradayMaker) quantity=Math.min(quantity,MAX_ORDER_SIZE);
        if(flow && !riskExit && !inventoryExit) {
            // A five-share minimum used to overshoot a one-share allocation need, causing a
            // reversing sale on the next decision. Matching accepts single shares; size to need.
            double gap=position.targetQuantity()-position.quantity();
            double need="BUY".equals(side)?Math.max(0,gap):Math.max(0,-gap);
            quantity=Math.min(quantity,(int)Math.floor(need));
        }
        if("BUY".equals(side)) quantity=Math.min(quantity,Math.min(p.positionLimit()-position.quantity(),
                (int)Math.min(Integer.MAX_VALUE,position.cash()/Math.max(1,(market?m.bestAsk()*1.03:price)*1.001))));
        else if("SELL".equals(side)) quantity=Math.min(quantity,position.quantity());
        else quantity=0;
        if(inventoryExit)quantity=Math.min(quantity,excessQuantity);
        // Never invent cash to reach a lot. Small sell remainders remain eligible for liquidation.
        if("BUY".equals(side) && quantity<(flow?1:MIN_ORDER_SIZE))quantity=0;
        return Math.max(0,quantity);
    }
}
