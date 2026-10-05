package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.gamestock.backend.market.BatchOrderBook.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketCapMatchingTest {
    private BatchOrderBook book(String code) {
        Map<Long,Account> accounts=new HashMap<>();
        accounts.put(1L,new Account(1,100_000_000,false,true));
        accounts.put(2L,new Account(2,0,false,true));
        var stocks=new HashMap<Long,Stock>();
        stocks.put(1L,new Stock(1,code,10000,10000,10000,10000,true));
        var holdings=new HashMap<PositionKey,Holding>();
        holdings.put(new PositionKey(2,1),new Holding(1000,1000,10000,0));
        return new BatchOrderBook(accounts,stocks,holdings,List.of(),0,1000);
    }

    @Test void fundedSmallCapRallyCrossesTenThirtyAndTwoHundredPercentWithoutFakePrices() {
        var book=book("SD");long time=1000;
        for(long price=10000;price<=30000;price+=500) {
            book.submit(2,1,"SELL","LIMIT",1,price,time++,10000);
            book.submit(1,1,"BUY","LIMIT",1,price,time++,10000);
        }
        assertEquals(30000,book.stocks.get(1L).last);
        assertTrue(book.stocks.get(1L).continuous);
        assertEquals(41,book.fills.size());
        long fees=book.fills.stream().mapToLong(f->f.buyerFee()+f.sellerFee()).sum();
        assertEquals(100_000_000,book.accounts.values().stream().mapToLong(a->a.cash).sum()+fees);
        assertEquals(1000,book.holdings.values().stream().mapToInt(Holding::quantity).sum());
        assertNull(book.submit(1,1,"BUY","LIMIT",1,30050,time,10000));
        assertNull(book.submit(2,1,"SELL","LIMIT",1,1995,time,10000));
    }

    @Test void widenedDailyRangeStillRejectsAnInstantTwentyPercentBotGap() {
        var book=book("SD");
        book.submit(2,1,"SELL","LIMIT",1,12000,1000,10000);
        book.submit(1,1,"BUY","LIMIT",1,12000,1001,10000);
        assertEquals(10000,book.stocks.get(1L).last);
        assertTrue(book.fills.isEmpty());
        assertTrue(book.stocks.get(1L).continuous);
    }
}
