package com.gamestock.backend.market;

/** Dimensionless pressures, never a price or a target price path. */
public record MarketEnvironment(double directionBias, double trendStrength, double volatilityMultiplier,
        double buyPressure, double sellPressure, double volumeMultiplier, double liquidityMultiplier,
        double meanReversionStrength, double breakoutProbability, double reversalProbability,
        double marketOrderMultiplier, double spreadMultiplier) {
    public static MarketEnvironment neutral() {
        return new MarketEnvironment(0, 0, 1, 1, 1, 1, 1, 0, 0, 0, 1, 1);
    }
    public MarketEnvironment combine(MarketEnvironment b) {
        return new MarketEnvironment(clamp(directionBias + b.directionBias, -2, 2),
                Math.max(trendStrength, b.trendStrength), clamp(volatilityMultiplier * b.volatilityMultiplier, .3, 5),
                buyPressure * b.buyPressure, sellPressure * b.sellPressure,
                clamp(volumeMultiplier * b.volumeMultiplier, .2, 3),
                clamp(liquidityMultiplier * b.liquidityMultiplier, .15, 2),
                Math.max(meanReversionStrength, b.meanReversionStrength),
                Math.max(breakoutProbability, b.breakoutProbability), Math.max(reversalProbability, b.reversalProbability),
                clamp(marketOrderMultiplier * b.marketOrderMultiplier, .2, 4),
                clamp(spreadMultiplier * b.spreadMultiplier, .5, 5));
    }
    public static double clamp(double n, double lo, double hi) { return Math.max(lo, Math.min(hi, n)); }
}
