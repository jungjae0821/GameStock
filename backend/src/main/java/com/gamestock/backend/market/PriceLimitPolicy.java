package com.gamestock.backend.market;

/**
 * Calculates the daily trading range from one fixed reference price.
 * Human orders may use the full Korean-market-style +/-30% range, while
 * automated liquidity bots stay inside a narrower +/-20% range so they
 * cannot create an upper-limit or lower-limit price by themselves.
 */
final class PriceLimitPolicy {
    static final double DAILY_LIMIT_RATE = 0.30;
    static final double BOT_DAILY_LIMIT_RATE = 0.20;
    static final double MARKET_EXECUTION_RATE = 0.10;

    private PriceLimitPolicy() { }

    static PriceBand dailyBand(long referencePrice) {
        return band(referencePrice, DAILY_LIMIT_RATE);
    }

    static PriceBand botBand(long referencePrice) {
        return band(referencePrice, BOT_DAILY_LIMIT_RATE);
    }

    static PriceBand marketExecutionBand(long referencePrice) {
        return band(referencePrice, MARKET_EXECUTION_RATE);
    }

    static PriceBand band(long referencePrice, double rate) {
        if (referencePrice <= 0) throw new IllegalArgumentException("기준가는 0보다 커야 합니다.");
        if (rate <= 0 || rate >= 1) throw new IllegalArgumentException("가격제한 비율이 올바르지 않습니다.");

        long referenceTick = tickSize(referencePrice);
        long rawLimit = (long) Math.floor(referencePrice * rate);
        long limitAmount = Math.max(referenceTick, (rawLimit / referenceTick) * referenceTick);
        long lower = ceilToTick(Math.max(100, referencePrice - limitAmount));
        long upper = floorToTick(referencePrice + limitAmount);
        return new PriceBand(referencePrice, lower, upper);
    }

    static long tickSize(long price) {
        if (price <= 1_000) return 1;
        if (price <= 5_000) return 5;
        if (price <= 50_000) return 10;
        if (price <= 100_000) return 50;
        return 100;
    }

    static long floorToTick(double price) {
        long floored = Math.max(1, (long) Math.floor(price));
        long tick = tickSize(floored);
        return Math.max(tick, (floored / tick) * tick);
    }

    static long ceilToTick(double price) {
        long ceiled = Math.max(1, (long) Math.ceil(price));
        long tick = tickSize(ceiled);
        return Math.max(tick, ((ceiled + tick - 1) / tick) * tick);
    }

    record PriceBand(long referencePrice, long lowerPrice, long upperPrice) {
        long clamp(long price) {
            return Math.max(lowerPrice, Math.min(upperPrice, price));
        }

        boolean contains(long price) {
            return price >= lowerPrice && price <= upperPrice;
        }
    }
}
