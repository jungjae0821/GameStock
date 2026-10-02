package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommittedTradeCounterTest {
    @Test void rollingWindowExcludesOldAndFutureFillsAndKeepsSymbolsIndependent() {
        var window=new CommittedTradeCounter();
        window.add(1,1000,8);window.add(1,1500,9);window.add(1,2500,10);window.add(2,1800,4);
        assertEquals(17,window.recent(2000).get(1L));assertEquals(4,window.recent(2000).get(2L));
        assertEquals(9,window.recent(2001).get(1L));assertEquals(10,window.recent(2600).get(1L));
        assertEquals(0,window.recent(4000).get(1L));
    }
}
