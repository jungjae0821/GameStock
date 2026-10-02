package com.gamestock.backend.market;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.MarketModels.*;
import static com.gamestock.backend.market.MarketExecutionLocks.*;

class MarketExecutionLocksTest {
    private Resting ask(long user,long price,int quantity) {return new Resting(user,user,"SELL",price,quantity,quantity,false,false);}
    private Resting bid(long user,long price,int quantity) {return new Resting(user,user,"BUY",price,quantity,0,false,false);}
    @Test void aSmallBuyDoesNotLockUnreachableLpLevels() {
        var book=List.of(bid(99,9970,20),ask(2,10000,3),ask(99,10030,20));
        assertEquals(Set.of(1L,2L),accountsFor(new OrderRequest("UMA","BUY",1,"MARKET",null),1,book,10000,10000));
        assertEquals(Set.of(1L),accountsFor(new OrderRequest("UMA","SELL",1,"LIMIT",10000L),1,book,10000,10000));
        assertEquals(Set.of(1L,2L,99L),accountsFor(new OrderRequest("UMA","BUY",4,"MARKET",null),1,book,10000,10000));
    }
    @Test void expiredRefundsAndInvalidQuotesStayCovered() {
        var book=List.of(new Resting(9,9,"BUY",9900,1,0,false,true),ask(2,7000,100),ask(3,10000,1));
        assertEquals(Set.of(1L,2L,3L,9L),accountsFor(new OrderRequest("UMA","BUY",1,"MARKET",null),1,book,10000,10000));
    }
    @Test void sellersLockLaterBuyersInCaseRoundedFeesCancelTheFirst() {
        var book=List.of(bid(2,10010,100),bid(3,10000,10),bid(99,9990,10));
        assertEquals(Set.of(1L,2L,3L),accountsFor(new OrderRequest("UMA","SELL",1,"LIMIT",10000L),1,book,10000,10000));
    }
    @Test void legacyCrossedBooksUseConservativeAccountLocks() {
        var book=List.of(bid(2,10010,10),ask(3,10000,10),ask(99,10200,10));
        assertEquals(Set.of(1L,2L,3L,99L),accountsFor(new OrderRequest("UMA","BUY",1,"LIMIT",9900L),1,book,10000,10000));
    }
}
