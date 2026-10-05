package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockMarketProfileTest {
    @Test
    void virtualMarketCapsDifferentiateStocksAndTheirLiquidity() {
        var small = StockMarketProfile.of("SD");
        var large = StockMarketProfile.of("GI");
        var otherSmall = StockMarketProfile.of("AK");
        var otherLarge = StockMarketProfile.of("WH");

        assertEquals(small.activeUsers() * StockMarketProfile.VIRTUAL_VALUE_PER_ACTIVE_USER, small.marketCap());
        assertEquals(5_000_000_000L, small.marketCap());
        assertEquals(1_500_000_000_000L, large.marketCap());
        assertTrue(small.marketCap() < otherSmall.marketCap());
        assertTrue(small.movementWeight() > otherSmall.movementWeight());
        assertTrue(large.activeUsers() > small.activeUsers());
        assertTrue(large.marketCap() > small.marketCap());
        assertTrue(large.movementWeight() < small.movementWeight());
        assertTrue(large.liquidityWeight() > small.liquidityWeight());
        assertTrue(large.marketCap() > otherLarge.marketCap());
        assertTrue(large.liquidityWeight() >= otherLarge.liquidityWeight());
    }

    @Test
    void largerMarketCapDampensNoiseAndAddsRestoringPressure() {
        var base = new MarketEnvironment(.8, .6, 2.4, 1.8, 1.1, 1.5, .7, .5, .8, .4, 1.9, 2.1);
        var small = StockMarketProfile.applyTo("SD", base);
        var large = StockMarketProfile.applyTo("GI", base);

        assertTrue(small.directionBias() > base.directionBias());
        assertTrue(Math.abs(large.directionBias()) < Math.abs(base.directionBias()));
        assertTrue(large.volatilityMultiplier() < base.volatilityMultiplier());
        assertTrue(large.buyPressure() < base.buyPressure());
        assertTrue(large.spreadMultiplier() < base.spreadMultiplier());
        assertTrue(large.meanReversionStrength() > small.meanReversionStrength());
        assertEquals(.03, StockMarketProfile.normalDailyMove("GI"));
        assertTrue(StockMarketProfile.activityTarget("GI",300)<StockMarketProfile.activityTarget("SD",300));
        assertEquals(10000, StockMarketProfile.valuationAnchor("GI",10000,16000,0));
        assertEquals(11000, StockMarketProfile.valuationAnchor("GI",10000,16000,.1),1e-9);
    }

    @Test
    void publicStockCarriesTheProfileUsedByTheMarket() {
        var profile = StockMarketProfile.of("BA");
        var stock = new MarketModels.Stock("BA", "BA", "RPG", 10_000, 0, 0);

        assertEquals(profile.activeUsers(), stock.activeUsers());
        assertEquals(profile.marketCap(), stock.marketCap());
        assertEquals(profile.movementWeight(), stock.movementWeight(), 1e-12);
    }

    @Test void apiCarriesExactDailyLimitsAndReferenceEvenAfterATriple() {
        var small=new MarketModels.Stock("SD","SD","RPG",30000,200,1,null,10000);
        assertEquals(10000,small.referencePrice());
        assertEquals(30000,small.limitUp());
        assertEquals(2000,small.limitDown());
        var large=new MarketModels.Stock("GI","GI","RPG",10100,1,1,null,10000);
        assertEquals(13000,large.limitUp());
        assertEquals(7000,large.limitDown());
    }
}
