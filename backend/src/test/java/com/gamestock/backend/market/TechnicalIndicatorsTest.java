package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TechnicalIndicatorsTest {
    @Test void smaAndEmaUseSeedAndWarmup() {
        double[] x={1,2,3,4,5};
        assertArrayEquals(new double[]{Double.NaN,Double.NaN,2,3,4},TechnicalIndicators.sma(x,3));
        assertArrayEquals(new double[]{Double.NaN,Double.NaN,2,3,4},TechnicalIndicators.ema(x,3));
        assertArrayEquals(new double[]{Double.NaN,Double.NaN,Double.NaN,2,3},TechnicalIndicators.sma(new double[]{Double.NaN,1,2,3,4},3));
    }
    @Test void wilderRsiDistinguishesRisingFallingAndFlat() {
        assertArrayEquals(new double[]{Double.NaN,Double.NaN,100,100},TechnicalIndicators.rsi(new double[]{1,2,3,4},2));
        assertArrayEquals(new double[]{Double.NaN,Double.NaN,0,0},TechnicalIndicators.rsi(new double[]{4,3,2,1},2));
        assertEquals(50,TechnicalIndicators.rsi(new double[]{2,2,2},2)[2]);
        assertEquals(50,TechnicalIndicators.rsi(new double[]{1,3,1},2)[2]);
    }
    @Test void populationDeviationAndTrueRangeUseGaps() {
        double[] sd=TechnicalIndicators.stddev(new double[]{1,2,3,4,5},3);
        for(int i=2;i<5;i++)assertEquals(Math.sqrt(2.0/3),sd[i],1e-10);
        assertArrayEquals(new double[]{Double.NaN,4,3.5},TechnicalIndicators.atr(new double[]{11,16,17},new double[]{9,14,14},new double[]{10,15,16},2));
    }
    @Test void slidingVarianceMatchesIndependentReferenceWithLargePrices() {
        double[] input=new double[1000];for(int i=0;i<input.length;i++)input[i]=1e9+Math.sin(i)*1000;
        double[] actual=TechnicalIndicators.stddev(input,20);
        for(int i=19;i<input.length;i++) {
            double mean=0,variance=0;for(int j=i-19;j<=i;j++)mean+=input[j]/20;
            for(int j=i-19;j<=i;j++)variance+=Math.pow(input[j]-mean,2)/20;
            assertEquals(Math.sqrt(variance),actual[i],0.01);
        }
    }
}
