package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockMarketProfileTest {
    @Test
    void activeUsersDriveMarketCapAndSmallestStockKeepsExistingMovement() {
        var small = StockMarketProfile.of("SD");
        var large = StockMarketProfile.of("GI");
        var otherSmall = StockMarketProfile.of("AK");
        var otherLarge = StockMarketProfile.of("WH");

        assertEquals(small.activeUsers() * StockMarketProfile.VIRTUAL_VALUE_PER_ACTIVE_USER, small.marketCap());
        assertEquals(1.0, small.movementWeight(), 1e-12);
        assertEquals(small.activeUsers(), otherSmall.activeUsers());
        assertEquals(small.marketCap(), otherSmall.marketCap());
        assertTrue(large.activeUsers() > small.activeUsers());
        assertTrue(large.marketCap() > small.marketCap());
        assertTrue(large.movementWeight() < small.movementWeight());
        assertTrue(large.liquidityWeight() > small.liquidityWeight());
        assertEquals(large.activeUsers(), otherLarge.activeUsers());
        assertEquals(large.marketCap(), otherLarge.marketCap());
        assertEquals(large.movementWeight(), otherLarge.movementWeight(), 1e-12);
    }

    @Test
    void largerMarketCapDampensSignalsButPreservesTheSmallStockBaseline() {
        var base = new MarketEnvironment(.8, .6, 2.4, 1.8, 1.1, 1.5, .7, .5, .8, .4, 1.9, 2.1);
        var small = StockMarketProfile.applyTo("SD", base);
        var large = StockMarketProfile.applyTo("GI", base);

        assertEquals(base, small);
        assertTrue(Math.abs(large.directionBias()) < Math.abs(base.directionBias()));
        assertTrue(large.volatilityMultiplier() < base.volatilityMultiplier());
        assertTrue(large.buyPressure() < base.buyPressure());
        assertTrue(large.spreadMultiplier() < base.spreadMultiplier());
    }

    @Test
    void publicStockCarriesTheProfileUsedByTheMarket() {
        var profile = StockMarketProfile.of("BA");
        var stock = new MarketModels.Stock("BA", "BA", "RPG", 10_000, 0, 0);

        assertEquals(profile.activeUsers(), stock.activeUsers());
        assertEquals(profile.marketCap(), stock.marketCap());
        assertEquals(profile.movementWeight(), stock.movementWeight(), 1e-12);
    }
}
