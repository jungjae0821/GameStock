package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class TapOddsTest {
    @Test
    void cellHoldingTheCurrentPriceIsTheMostLikely() {
        double inside = TapOdds.touchProbability(12_150, 10, 2_000, 5_000, 12_150, 12_160, 10);
        double above = TapOdds.touchProbability(12_150, 10, 2_000, 5_000, 12_160, 12_170, 10);
        assertTrue(inside > 0.7 && inside > above, inside + " vs " + above);
        assertEquals(0, TapOdds.multiplier(0.95), "거의 확실한 칸은 배당이 1.1 밑이라 걸 수 없다");
    }

    @Test
    void fartherAndSoonerCellsPayMore() {
        double near = TapOdds.touchProbability(12_150, 10, 2_000, 5_000, 12_180, 12_190, 10);
        double far = TapOdds.touchProbability(12_150, 10, 2_000, 5_000, 12_220, 12_230, 10);
        double later = TapOdds.touchProbability(12_150, 10, 20_000, 5_000, 12_220, 12_230, 10);
        assertTrue(near > far);
        assertTrue(later > far, "시간이 지날수록 먼 칸에도 닿기 쉽다");
        assertTrue(TapOdds.multiplier(0.2) > TapOdds.multiplier(0.5));
        assertEquals(4.6, TapOdds.multiplier(0.2));
        assertEquals(0, TapOdds.multiplier(0.01), "공정 배당 92×는 상한 30×를 넘어 열지 않는다");
    }

    @Test
    void multipliersKeepTheHouseEdgeAgainstSimulatedPaths() {
        // 칸마다 몬테카를로로 실제 닿는 비율을 재고, 배당 × 확률이 1을 넘지 않는지 본다.
        SplittableRandom random = new SplittableRandom(7);
        double price = 50_000, sigma = 40, tick = 50;
        long lead = 3_000, length = 5_000, step = 100;
        for (int rowsAway = 1; rowsAway <= 4; rowsAway++) {
            double low = price + rowsAway * 50, high = low + 50;
            int hits = 0, trials = 20_000;
            for (int trial = 0; trial < trials; trial++) {
                double x = price;
                boolean hit = false;
                for (long t = step; t <= lead + length; t += step) {
                    x += random.nextGaussian() * sigma * Math.sqrt(step / 1000.0);
                    double traded = Math.round(x / tick) * tick;
                    if (t > lead && traded >= low && traded < high) { hit = true; break; }
                }
                if (hit) hits++;
            }
            double observed = hits / (double) trials;
            double multiplier = TapOdds.multiplier(TapOdds.touchProbability(price, sigma, lead, length, low, high, tick));
            if (multiplier > 0) assertTrue(observed * multiplier < 1.0, rowsAway + "칸 위: 관측 " + observed + ", 배당 " + multiplier);
        }
    }

    @Test
    void normalCdfMatchesKnownValues() {
        assertEquals(0.5, TapOdds.normalCdf(0), 1e-7);
        assertEquals(0.8413, TapOdds.normalCdf(1), 1e-3);
        assertEquals(0.0228, TapOdds.normalCdf(-2), 1e-3);
    }
}
