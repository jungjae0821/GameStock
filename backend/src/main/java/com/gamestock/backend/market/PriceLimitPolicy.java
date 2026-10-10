package com.gamestock.backend.market;

/**
 * Daily ranges use the fixed Korean trading-day reference. Every stock uses
 * the same previous-close +/-30% upper/lower limit.
 */
final class PriceLimitPolicy {
    static final double DAILY_LIMIT_RATE = 0.30;
    static final double BOT_DAILY_LIMIT_RATE = 0.20;
    static final double MARKET_EXECUTION_RATE = 0.10;

    private PriceLimitPolicy() { }

    static PriceBand dailyBand(long referencePrice) {
        return band(referencePrice, DAILY_LIMIT_RATE);
    }

    static PriceBand dailyBand(String code, long referencePrice) {
        return dailyBand(referencePrice);
    }

    static PriceBand botBand(long referencePrice) {
        return band(referencePrice, BOT_DAILY_LIMIT_RATE);
    }

    static PriceBand botBand(String code, long referencePrice) {
        return botBand(referencePrice);
    }

    static PriceBand quoteBand(String code, long referencePrice, double news) {
        if (!StockMarketProfile.isLargeCap(code)) return botBand(code, referencePrice);
        // A wider safety envelope surrounds the +/-3% soft target. News expands it.
        return band(referencePrice, Math.min(.20, .06 + Math.abs(news)));
    }

    static double dynamicViRate(String code) { return StockMarketProfile.isSpeculative(code) ? .20 : .06; }
    static double staticViRate(String code) { return StockMarketProfile.isSpeculative(code) ? Double.POSITIVE_INFINITY : .10; }

    static PriceBand marketExecutionBand(long referencePrice) {
        return band(referencePrice, MARKET_EXECUTION_RATE);
    }

    static PriceBand band(long referencePrice, double rate) {
        if (rate <= 0 || rate >= 1) throw new IllegalArgumentException("가격제한 비율이 올바르지 않습니다.");
        return band(referencePrice, rate, rate);
    }

    static PriceBand band(long referencePrice, double downRate, double upRate) {
        if (referencePrice <= 0) throw new IllegalArgumentException("기준가는 0보다 커야 합니다.");
        if (!Double.isFinite(downRate) || !Double.isFinite(upRate) || downRate <= 0 || downRate >= 1 || upRate <= 0 || upRate > 2)
            throw new IllegalArgumentException("가격제한 비율이 올바르지 않습니다.");

        long referenceTick = tickSize(referencePrice);
        long down = Math.max(referenceTick, (long)Math.floor(referencePrice * downRate / referenceTick) * referenceTick);
        long up = Math.max(referenceTick, (long)Math.floor(referencePrice * upRate / referenceTick) * referenceTick);
        long lower = ceilToTick(Math.max(100, referencePrice - down));
        long upper = floorToTick(Math.addExact(referencePrice, up));
        return new PriceBand(referencePrice, lower, upper);
    }

    static long tickSize(long price) {
        if (price < 2_000) return 1;
        if (price < 5_000) return 5;
        if (price < 20_000) return 10;
        if (price < 50_000) return 50;
        if (price < 200_000) return 100;
        if (price < 500_000) return 500;
        return 1_000;
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

    /** Move one legal quote level upward, including across a price-band boundary. */
    static long nextTickPrice(long price) {
        if (price >= Long.MAX_VALUE - 1) return price;
        return ceilToTick(price + 1.0);
    }

    /** Move one legal quote level downward, including across a price-band boundary. */
    static long previousTickPrice(long price) {
        return floorToTick(Math.max(1, price - 1.0));
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
