package com.gamestock.backend.market;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic virtual size/liquidity inputs for the simulated game market.
 *
 * The active-user figures are simulation inputs, not live analytics. They make
 * the relationship between a game's audience and its virtual market size
 * explicit while keeping the market reproducible across restarts.
 */
public final class StockMarketProfile {
    public static final long VIRTUAL_VALUE_PER_ACTIVE_USER = 100_000L;
    public static final long SMALL_CAP_ACTIVE_USERS = 450_000L;

    /** The user-selected large-cap bucket. Every other catalog symbol is small-cap. */
    private static final Set<String> LARGE_CAP_CODES = Set.of(
            "WH", "ZZZ", "GOV", "GI", "BA", "UMA", "SR", "EL");

    // Game balance inputs, not measured users or valuations. Keep marketCap.ts aligned.
    private static final Map<String, Long> ACTIVE_USERS = Map.ofEntries(
            Map.entry("GI", 15_000_000L), Map.entry("SR", 12_000_000L),
            Map.entry("EL", 10_000_000L), Map.entry("ZZZ", 8_000_000L),
            Map.entry("WH", 7_000_000L), Map.entry("GOV", 6_000_000L),
            Map.entry("BA", 4_000_000L), Map.entry("UMA", 3_000_000L),
            Map.entry("AK", 600_000L), Map.entry("LT", 500_000L),
            Map.entry("MH", 400_000L), Map.entry("PW", 200_000L),
            Map.entry("PX", 150_000L), Map.entry("ES", 100_000L), Map.entry("SD", 50_000L));

    public record Profile(String code, long activeUsers, long marketCap,
                          double movementWeight, double liquidityWeight) { }

    private StockMarketProfile() { }

    public static boolean isLargeCap(String code) {
        String normalized = code == null ? "" : code.toUpperCase(Locale.ROOT);
        return LARGE_CAP_CODES.contains(normalized);
    }

    public static Profile of(String code) {
        String normalized = code == null ? "" : code.toUpperCase(Locale.ROOT);
        boolean largeCap = isLargeCap(normalized);
        long activeUsers = ACTIVE_USERS.getOrDefault(normalized, SMALL_CAP_ACTIVE_USERS);
        double size = Math.sqrt(activeUsers / (double)SMALL_CAP_ACTIVE_USERS);
        double movementWeight = largeCap ? Math.max(.10, .45 / size) : Math.min(2.5, 1 / size);
        double liquidityWeight = largeCap ? Math.min(10, 2 * size) : Math.max(.35, size * .65);
        return new Profile(normalized, activeUsers, activeUsers * VIRTUAL_VALUE_PER_ACTIVE_USER,
                movementWeight, liquidityWeight);
    }

    public static boolean isSpeculative(String code) {
        String normalized = code == null ? "" : code.toUpperCase(Locale.ROOT);
        return ACTIVE_USERS.containsKey(normalized) && !isLargeCap(normalized);
    }

    /** Ordinary large-cap fluctuations are a soft daily target, not a forced price path. */
    public static double normalDailyMove(String code) {
        return isLargeCap(code) ? .03 : Math.min(1.2, .6 * of(code).movementWeight());
    }

    public static double decisionIntervalWeight(String code) {
        return isLargeCap(code) ? Math.max(6, of(code).liquidityWeight()) : 1;
    }

    public static int activityTarget(String code, int base) {
        return isLargeCap(code) ? Math.max(2, (int)Math.round(base * .12)) : base;
    }

    /** Large-cap value estimates use today's reference plus actual news, not an old startup anchor. */
    public static double valuationAnchor(String code, long dayReference, double fundamental, double news) {
        return isLargeCap(code) ? dayReference * (1 + MarketEnvironment.clamp(news, -.15, .15)) : fundamental;
    }

    /** Apply market-size elasticity to directional and volatility signals. */
    public static MarketEnvironment applyTo(String code, MarketEnvironment base) {
        double movement = of(code).movementWeight();
        return new MarketEnvironment(
                base.directionBias() * movement,
                base.trendStrength() * movement,
                1 + (base.volatilityMultiplier() - 1) * movement,
                1 + (base.buyPressure() - 1) * movement,
                1 + (base.sellPressure() - 1) * movement,
                base.volumeMultiplier(),
                base.liquidityMultiplier(),
                isLargeCap(code) ? Math.max(.8, base.meanReversionStrength()) : base.meanReversionStrength() / Math.max(1, movement),
                base.breakoutProbability() * movement,
                base.reversalProbability() * movement,
                1 + (base.marketOrderMultiplier() - 1) * movement,
                1 + (base.spreadMultiplier() - 1) * movement);
    }

    /** Scale only the liquidity budget; the smallest stock stays unchanged. */
    public static double liquidityScale(String code, double baseScale) {
        return Math.min(10, Math.max(.1, baseScale) * of(code).liquidityWeight());
    }

    /** Give larger virtual companies enough inventory to replenish both sides. */
    public static int initialLiquidityInventory(String code, int baseInventory) {
        return Math.max(32, (int) Math.ceil(baseInventory * of(code).liquidityWeight()));
    }
}
