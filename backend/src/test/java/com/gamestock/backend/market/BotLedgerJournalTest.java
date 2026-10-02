package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.BotLedgerJournal.*;

class BotLedgerJournalTest {
    @Test void compactPayloadRetainsEveryExecutionAndAccountCheckpoint() {
        var holding=new BatchOrderBook.Holding(3,3,10000,40);
        var fill=new Execution(1,2,3,4,3,4,1,10020,10,10,"BUY","MARKET",holding,holding);
        var batch=new Batch(1234,Collections.nCopies(100,fill),List.of(new Cash(1,20000,9970)),List.of(new Position(1,holding)),
                List.of(new OrderState(3,1,"BUY","LIMIT",10020,1,0,0,0,"FILLED",1200,5000,false)));
        byte[] payload=encode(batch);
        assertEquals(batch,decode(payload));assertTrue(payload.length<1000,"batch compression should remove repeated execution fields");
    }
    @Test void corruptedOrTruncatedJournalIsRejectedInsteadOfReturningPartialFills() {
        byte[] payload=encode(new Batch(1,List.of(),List.of(),List.of(),List.of()));
        payload[payload.length-5]^=1;
        assertThrows(IllegalStateException.class,()->decode(payload));
        assertThrows(IllegalStateException.class,()->decode(Arrays.copyOf(payload,10)));
    }
}
