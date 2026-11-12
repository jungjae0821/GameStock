package com.gamestock.backend.market;

import static com.gamestock.backend.market.BotProfile.*;
import static com.gamestock.backend.market.MarketEnvironment.clamp;

/**
 * Per-strategy signal math split out of {@link BotStrategyEngine}: each strategy's raw
 * directional signal, the family-specific valuation horizon, the executable edge used to
 * decide whether a spread is worth paying, and the dip/protective-exit triggers.
 * Pure functions with no state and no random draws; the engine keeps every Random call.
 */
final class BotStrategySignals {
    private static final double LARGE_CAP_DIP_TRIGGER = .01;
    private static final double LARGE_CAP_DIP_RANGE = .03;
    private static final double SPECULATIVE_DIP_TRIGGER = .04;
    private static final double SPECULATIVE_DIP_RANGE = .20;

    private BotStrategySignals() {}

    /** The strategy's own signal before environment, noise, inventory and P&L adjustments. */
    static double strategySignal(BotProfile p,PriceMetricService.Metrics m,MarketEnvironment e,
                                 double estimatedValue,double perceivedNews,double noise) {
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
        return signal;
    }

    /** Each family researches a different horizon; a common stochastic value is only one input. */
    static double valuationObservation(BotProfile p,PriceMetricService.Metrics m,double fundamental,double consensus) {
        return switch(p.strategy().family()) {
            case VALUE -> .85*fundamental+.15*consensus;
            case NEWS -> .6*fundamental+.4*consensus;
            case MOMENTUM -> .7*fundamental+.2*m.lastPrice()+.1*consensus;
            case CONTRARIAN -> .35*m.referenceVwap()+.65*fundamental;
            case INTRADAY -> .75*fundamental+.2*m.microPrice()+.05*m.vwap();
            case SWING -> .5*fundamental+.5*m.referenceVwap();
            case NOISE, AGGRESSIVE_LIQUIDITY_TAKER -> .8*m.vwap()+.2*fundamental;
        };
    }

    /** Liquid short-horizon strategies price execution risk rather than repeatedly reset to value. */
    static double valuationObservation(BotProfile p,PriceMetricService.Metrics m,double fundamental,double consensus,String symbol) {
        if(!StockMarketProfile.isLargeCap(symbol))return valuationObservation(p,m,fundamental,consensus);
        return switch(p.strategy().family()) {
            case MOMENTUM -> .1*fundamental+.2*m.executionVwap()+.7*consensus;
            case INTRADAY -> .75*consensus+.2*m.microPrice()+.05*m.vwap();
            case NOISE, AGGRESSIVE_LIQUIDITY_TAKER -> .8*consensus+.2*m.executionVwap();
            case SWING -> .5*fundamental+.5*consensus;
            default -> valuationObservation(p,m,fundamental,consensus);
        };
    }

    static double executableEdge(BotProfile p,PriceMetricService.Metrics m,String side,double estimatedValue) {
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

    static double dipStrength(PriceMetricService.Metrics m,String symbol,long dayReference) {
        if(dayReference<=0 || m.askDepth()<=0) return 0;
        boolean large=StockMarketProfile.isLargeCap(symbol);
        double trigger=large?LARGE_CAP_DIP_TRIGGER:SPECULATIVE_DIP_TRIGGER;
        double range=large?LARGE_CAP_DIP_RANGE:SPECULATIVE_DIP_RANGE;
        double drawdown=clamp((dayReference-m.markPrice())/Math.max(1.0,dayReference),0,1);
        return clamp((drawdown-trigger)/Math.max(.001,range-trigger),0,1);
    }

    static boolean protectiveExit(BotProfile p,PriceMetricService.Metrics m,BotStrategyEngine.Position position) {
        if(position.quantity()<=0 || position.averagePrice()<=0) return false;
        double returnFromAverage=m.bestBid()/position.averagePrice()-1;
        return returnFromAverage<=-p.stopLoss()
                || (returnFromAverage>=p.takeProfit() && returnFromAverage>2*.001);
    }

    static double intradayReference(BotProfile p,PriceMetricService.Metrics m) {
        double reference=switch(p.strategy()) {
            case VWAP_SCALPER -> .7*m.vwap()+.3*m.microPrice();
            case BOOK_IMBALANCE -> m.microPrice()*(1+m.imbalance()*.0005);
            case MICRO_TREND, BREAKOUT_SCALPER -> m.microPrice()*(1+clamp(m.return5s()*.5+m.return20s()*.2,-.005,.005));
            default -> .5*m.vwap()+.5*m.microPrice();
        };
        return reference*(1+(p.buyBias()-p.sellBias())*.002);
    }
}
