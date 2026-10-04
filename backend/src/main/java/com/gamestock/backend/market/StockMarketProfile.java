package com.gamestock.backend.market;

import java.util.Locale;
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
    public static final long LARGE_CAP_ACTIVE_USERS = 8_000_000L;
    public static final long SMALL_CAP_ACTIVE_USERS = 450_000L;

    /** The user-selected large-cap bucket. Every other catalog symbol is small-cap. */
    private static final Set<String> LARGE_CAP_CODES = Set.of(
            "WH", "ZZZ", "GOV", "GI", "BA", "UMA", "SR", "EL");

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
        long activeUsers = largeCap ? LARGE_CAP_ACTIVE_USERS : SMALL_CAP_ACTIVE_USERS;
        // The small-cap bucket keeps the existing movement. Large games absorb
        // the same order/news shock across a larger virtual value base.
        double movementWeight = largeCap ? .55 : 1.0;
        // More users also mean a deeper simulated book for the liquidity bot.
        double liquidityWeight = largeCap ? 1.65 : 1.0;
        return new Profile(normalized, activeUsers, activeUsers * VIRTUAL_VALUE_PER_ACTIVE_USER,
                movementWeight, liquidityWeight);
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
                base.meanReversionStrength() * movement,
                base.breakoutProbability() * movement,
                base.reversalProbability() * movement,
                1 + (base.marketOrderMultiplier() - 1) * movement,
                1 + (base.spreadMultiplier() - 1) * movement);
    }

    /** Scale only the liquidity budget; the smallest stock stays unchanged. */
    public static double liquidityScale(String code, double baseScale) {
        return Math.min(2.75, Math.max(.1, baseScale) * of(code).liquidityWeight());
    }

    /** Give larger virtual companies enough inventory to replenish both sides. */
    public static int initialLiquidityInventory(String code, int baseInventory) {
        return Math.max(baseInventory, (int) Math.ceil(baseInventory * of(code).liquidityWeight()));
    }
}
