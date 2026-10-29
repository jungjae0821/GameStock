package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchChartServiceTest {
    @Test void indicatorsAlignWithCandlesAndStateVolumeUnits() {
        MarketService market=mock(MarketService.class);
        when(market.chartHistory("GI","1h")).thenReturn(QuantCompilerTest.bars(10,12,14));
        var chart=new ResearchChartService(market).chart("gi","1h",List.of(new ResearchChartService.Indicator("ma","SMA",2),new ResearchChartService.Indicator("bb","BBANDS",2)));
        assertEquals("TRADE_COUNT",chart.volumeUnit());assertEquals(60,chart.intervalSeconds());
        assertEquals(Arrays.asList(null,11.0,13.0),chart.indicators().get(0).values().get("value"));
        assertEquals(Arrays.asList(null,13.0,15.0),chart.indicators().get(1).values().get("upper"));
    }
    @Test void rejectsInvalidOrDuplicateIndicatorsBeforeQuerying() {
        MarketService market=mock(MarketService.class);var service=new ResearchChartService(market);
        var ma=new ResearchChartService.Indicator("ma","SMA",2);
        assertThrows(IllegalArgumentException.class,()->service.chart("GI","1h",List.of(ma,ma)));
        assertThrows(IllegalArgumentException.class,()->service.chart("GI","bad",List.of()));
        assertThrows(IllegalArgumentException.class,()->service.chart("GI",null,List.of()));
        assertThrows(IllegalArgumentException.class,()->service.chart("GI","1h",List.of(new ResearchChartService.Indicator("x","UNKNOWN",2))));
        verifyNoInteractions(market);
    }
    @Test void openCandleIsExcludedFromStrategyData() {
        MarketService market=mock(MarketService.class);Instant now=Instant.now();
        var closed=new MarketModels.ChartCandle(now.minusSeconds(120).toString(),1,1,1,1,1,now.toString());
        var open=new MarketModels.ChartCandle(now.toString(),100,100,100,100,1,now.toString());
        when(market.chartHistory("GI","1h")).thenReturn(List.of(closed,open));
        assertEquals(List.of(closed),new ResearchChartService(market).candles("GI","1h",true));
    }
}
