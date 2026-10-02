package com.gamestock.backend.market;

import java.util.*;

/** Shared global/sector shocks persist between updates; symbols are not independent markets. */
public final class MarketRegimeEngine {
    public enum Regime {
        STRONG_BULL(.65, .9, 1.4), BULL(.4, .7, 1.1), WEAK_BULL(.18, .4, .9),
        SIDEWAYS(0, .1, .8), WEAK_BEAR(-.18, .4, .9), BEAR(-.4, .7, 1.2),
        STRONG_BEAR(-.65, .9, 1.6), HIGH_VOLATILITY(0, .2, 2), LOW_VOLATILITY(0, .1, .45),
        PANIC(-.95, 1, 3), EUPHORIA(.95, 1, 2.2);
        final double bias, trend, volatility;
        Regime(double b, double t, double v) { bias=b; trend=t; volatility=v; }
    }
    private final Random random;
    private final Map<String, Double> sectors = new TreeMap<>(), symbols = new TreeMap<>();
    private Regime regime = Regime.SIDEWAYS;
    private long expires, nextFactors;
    private double global;
    public MarketRegimeEngine(long seed) { random = new Random(seed); }
    public Regime regime() { return regime; }
    public void advance(long now, Map<String, String> universe) {
        if (now >= expires) {
            // Adjacent directional regimes are favored, extremes remain possible.
            double total = 0;
            double[] weights = new double[Regime.values().length];
            for (Regime r : Regime.values()) {
                weights[r.ordinal()] = 1 / (1 + 4 * Math.abs(r.bias - regime.bias));
                total += weights[r.ordinal()];
            }
            double draw = random.nextDouble() * total;
            for (Regime r : Regime.values()) if ((draw -= weights[r.ordinal()]) <= 0) { regime = r; break; }
            expires = now + 120_000 + random.nextInt(240_001);
        }
        if (now < nextFactors) return;
        nextFactors = now + 10_000;
        global = .85 * global + random.nextGaussian() * .04;
        for (String sector : new TreeSet<>(universe.values()))
            sectors.put(sector, .9 * sectors.getOrDefault(sector, 0.0) + random.nextGaussian() * .04);
        for (String symbol : new TreeSet<>(universe.keySet()))
            symbols.put(symbol, .8 * symbols.getOrDefault(symbol, 0.0) + random.nextGaussian() * .06);
    }
    public MarketEnvironment environment(String symbol, String sector) {
        double bias = regime.bias + global + sectors.getOrDefault(sector, 0.0) + symbols.getOrDefault(symbol, 0.0);
        double liquidity = regime == Regime.PANIC ? .35 : regime == Regime.LOW_VOLATILITY ? 1.3 : .9;
        return new MarketEnvironment(bias, regime.trend, regime.volatility, 1 + Math.max(0, bias),
                1 + Math.max(0, -bias), Math.max(.5, regime.volatility), liquidity,
                regime == Regime.SIDEWAYS ? .5 : .1, .1, .1, Math.max(.5, regime.volatility), Math.max(.7, regime.volatility));
    }
}
