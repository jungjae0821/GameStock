package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceLimitPolicyTest {
    @Test void everyStockUsesTheSameThirtyPercentDailyBand() {
        for(String code:java.util.List.of("AK","LT","MH","PW","PX","ES","SD")) {
            var daily=PriceLimitPolicy.dailyBand(code,10000);
            var bots=PriceLimitPolicy.botBand(code,10000);
            assertEquals(7000,daily.lowerPrice());
            assertEquals(13000,daily.upperPrice());
            assertEquals(8000,bots.lowerPrice());
            assertEquals(12000,bots.upperPrice());
            assertEquals(Double.POSITIVE_INFINITY,PriceLimitPolicy.staticViRate(code));
        }
        assertEquals(7000,PriceLimitPolicy.dailyBand("GI",10000).lowerPrice());
        assertEquals(13000,PriceLimitPolicy.dailyBand("GI",10000).upperPrice());
        assertEquals(10600,PriceLimitPolicy.quoteBand("GI",10000,0).upperPrice());
        assertEquals(11600,PriceLimitPolicy.quoteBand("GI",10000,.1).upperPrice());
    }

    @Test
    void dailyBandMatchesKrxThirtyPercentExample() {
        PriceLimitPolicy.PriceBand band = PriceLimitPolicy.dailyBand(9_940L);

        assertEquals(6_960L, band.lowerPrice());
        assertEquals(12_920L, band.upperPrice());
    }

    @Test
    void botBandAlwaysStaysInsideDailyLimit() {
        PriceLimitPolicy.PriceBand daily = PriceLimitPolicy.dailyBand(12_450L);
        PriceLimitPolicy.PriceBand bot = PriceLimitPolicy.botBand(12_450L);

        assertTrue(bot.lowerPrice() > daily.lowerPrice());
        assertTrue(bot.upperPrice() < daily.upperPrice());
        assertEquals(bot.upperPrice(), bot.clamp(daily.upperPrice()));
        assertEquals(bot.lowerPrice(), bot.clamp(daily.lowerPrice()));
    }

    @Test
    void boundariesUseValidTickPrices() {
        PriceLimitPolicy.PriceBand band = PriceLimitPolicy.dailyBand(21_430L);

        assertEquals(0, band.lowerPrice() % PriceLimitPolicy.tickSize(band.lowerPrice()));
        assertEquals(0, band.upperPrice() % PriceLimitPolicy.tickSize(band.upperPrice()));
        assertTrue(band.contains(band.lowerPrice()));
        assertTrue(band.contains(band.upperPrice()));
    }

    @Test
    void tickSizeMatchesKrxStockPriceBands() {
        assertEquals(1L, PriceLimitPolicy.tickSize(1_999L));
        assertEquals(5L, PriceLimitPolicy.tickSize(2_000L));
        assertEquals(5L, PriceLimitPolicy.tickSize(4_999L));
        assertEquals(10L, PriceLimitPolicy.tickSize(5_000L));
        assertEquals(10L, PriceLimitPolicy.tickSize(19_999L));
        assertEquals(50L, PriceLimitPolicy.tickSize(20_000L));
        assertEquals(100L, PriceLimitPolicy.tickSize(50_000L));
        assertEquals(500L, PriceLimitPolicy.tickSize(200_000L));
        assertEquals(1_000L, PriceLimitPolicy.tickSize(500_000L));
    }

    @Test
    void nextAndPreviousTicksCrossKrxBoundaries() {
        assertEquals(2_000L, PriceLimitPolicy.nextTickPrice(1_999L));
        assertEquals(2_005L, PriceLimitPolicy.nextTickPrice(2_000L));
        assertEquals(1_999L, PriceLimitPolicy.previousTickPrice(2_000L));
        assertEquals(4_995L, PriceLimitPolicy.previousTickPrice(5_000L));
    }
}
