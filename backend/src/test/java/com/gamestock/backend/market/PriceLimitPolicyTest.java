package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceLimitPolicyTest {
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
}
