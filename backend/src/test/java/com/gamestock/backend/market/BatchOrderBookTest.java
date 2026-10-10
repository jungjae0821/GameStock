package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.BatchOrderBook.*;

class BatchOrderBookTest {
    @Test void databaseRetryReplaysTheSameOrderPricesQuantitiesAndSequence(){
        var original=book(1000000);original.capture();
        original.submit(2,1,"SELL","LIMIT",2,10000,1000,5000);
        original.submit(3,1,"SELL","LIMIT",3,10010,1000,5000);
        original.submit(1,1,"BUY","MARKET",4,0,1001,0);
        var plan=original.captured();var retry=book(1000000);plan.apply(retry,1010);
        assertEquals(original.accepted.stream().map(o->List.of(o.user,o.side,o.type,o.quantity,o.price)).toList(),retry.accepted.stream().map(o->List.of(o.user,o.side,o.type,o.quantity,o.price)).toList());
        assertEquals(List.of(2,2),retry.fills.stream().map(Fill::quantity).toList());
        assertEquals(original.accounts.get(1L).cash,retry.accounts.get(1L).cash);
    }
    @Test void retryCannotCancelAnUnrelatedCommittedOrderWhoseIdMatchesATemporaryId(){
        var original=book(1000000);original.capture();
        var local=original.submit(1,1,"BUY","LIMIT",5,9900,1000,1000);original.cancel(local);
        var plan=original.captured();var retry=book(0);
        var unrelated=retry.submit(2,1,"SELL","LIMIT",1,10100,1001,5000);unrelated.persisted=true;
        retry.beginBatch(1002);plan.apply(retry,1010);
        assertEquals(local.id,unrelated.id);assertEquals("OPEN",unrelated.status);assertTrue(retry.accepted.isEmpty());
    }
    @Test void retryDoesNotReviveExpiredOrderIntents(){
        var original=book(1000000);original.capture();original.submit(1,1,"BUY","LIMIT",5,9900,1000,10);
        var plan=original.captured();var retry=book(1000000);plan.apply(retry,1020);
        assertTrue(retry.accepted.isEmpty());assertEquals(1000000,retry.accounts.get(1L).cash);
    }
    private BatchOrderBook book(long cash) {
        Map<Long,Account> accounts=new HashMap<>();accounts.put(1L,new Account(1,cash,false));accounts.put(2L,new Account(2,1000000,false));accounts.put(3L,new Account(3,1000000,false));
        Map<Long,Stock> stocks=new HashMap<>();stocks.put(1L,new Stock(1,"A",10000,10000,10000,10000,true));
        Map<PositionKey,Holding> holdings=new HashMap<>();holdings.put(new PositionKey(2,1),new Holding(100,100,10000,0));holdings.put(new PositionKey(3,1),new Holding(100,100,10000,0));
        return new BatchOrderBook(accounts,stocks,holdings,List.of(),0,1000);
    }
    @Test void levelsPriorityPartialFillAndFeeRounding() {
        var b=book(30030);
        var first=b.submit(2,1,"SELL","LIMIT",1,10000,1000,5000);
        var second=b.submit(3,1,"SELL","LIMIT",10,10010,1000,5000);
        var buy=b.submit(1,1,"BUY","MARKET",3,0,1000,0);
        assertEquals("PARTIAL",buy.status);assertEquals(10000,b.accounts.get(1L).cash);
        assertEquals(List.of(1,1),b.fills.stream().map(Fill::quantity).toList());
        assertSame(first,b.fills.get(0).maker());assertSame(second,b.fills.get(1).maker());
        assertEquals(9,second.remaining);assertEquals(2,b.holding(1,1).quantity());
    }
    @Test void selfTradeCancelsNewerAndDoesNotBlockOtherAccounts() {
        var b=book(1000000);
        b.submit(2,1,"SELL","LIMIT",3,10000,1000,1000);
        var self=b.submit(2,1,"BUY","MARKET",3,0,1001,0);
        assertEquals("CANCELLED",self.status);assertTrue(b.fills.isEmpty());
        b.submit(1,1,"BUY","MARKET",3,0,1002,0);assertEquals(1,b.fills.size());
    }
    @Test void restingReservationExpiresOnceAndRollbackStateIsLocal() {
        var b=book(1000000);var order=b.submit(1,1,"BUY","LIMIT",3,9900,1000,100);
        long reserved=order.reservedCash;assertEquals(1000000-reserved,b.accounts.get(1L).cash);
        var expired=new BatchOrderBook(b.accounts,b.stocks,b.holdings,List.of(order),1,1100);
        expired.cancel(order);assertEquals(1000000,expired.accounts.get(1L).cash);assertEquals(0,order.reservedCash);
    }
    @Test void priceChangesRequireFillsAndViStopsBeforeTransfer() {
        var b=book(1000000);
        b.submit(2,1,"SELL","LIMIT",3,10600,1000,5000);
        b.submit(1,1,"BUY","LIMIT",3,10600,1001,5000);
        assertTrue(b.fills.isEmpty());assertTrue(b.prices.isEmpty());assertEquals(10000,b.stocks.get(1L).last);
        assertEquals(10600,b.stocks.get(1L).viTrigger);assertEquals(100,b.holding(2,1).quantity());
    }
    @Test void automatedOrderOutsideViBandIsCancelledWithoutTriggeringVi() {
        var b=book(1000000);
        b.accounts.put(4L,new Account(4,1000000,false,true));
        b.holdings.put(new PositionKey(2,1),new Holding(100,100,10000,0));
        var bot=b.submit(4,1,"BUY","LIMIT",3,10600,1000,5000);
        b.submit(2,1,"SELL","LIMIT",3,10600,1001,5000);
        assertEquals("CANCELLED",bot.status);
        assertTrue(b.fills.isEmpty());
        assertTrue(b.stocks.get(1L).continuous);
        assertEquals(0,b.stocks.get(1L).viTrigger);
    }
    @Test void incomingOrdersExecuteSequentiallyNotResortedAsOneBatch() {
        var b=book(1000000);
        b.submit(2,1,"SELL","LIMIT",2,10000,1000,5000);
        var early=b.submit(1,1,"BUY","LIMIT",1,10000,1001,5000);
        var late=b.submit(3,1,"BUY","MARKET",1,0,1002,0);
        assertSame(early,b.fills.get(0).buy());assertSame(late,b.fills.get(1).buy());
    }
    @Test void lpRiskBudgetTracksReservationsFeesAndFillsWithoutBorrowingAnotherSymbolsAllocation() {
        var b=book(1000000);b.accounts.put(1L,new Account(1,1000000,true));
        b.lpCashBudgets.put(1L,50000L);b.lpCashBudgets.put(2L,25000L);
        var bid=b.submit(1,1,"BUY","LIMIT",2,10000,1000,4000);
        assertEquals(29980,b.lpCashBudgets.get(1L));
        b.submit(2,1,"SELL","MARKET",1,0,1001,0);b.cancel(bid);
        assertEquals(39990,b.lpCashBudgets.get(1L));
        b.submit(3,1,"BUY","LIMIT",1,10000,1002,4000);
        b.submit(1,1,"SELL","MARKET",1,0,1003,0);
        assertEquals(49980,b.lpCashBudgets.get(1L));assertEquals(25000,b.lpCashBudgets.get(2L));
        b.beginBatch(1004);assertEquals(49980,b.lpCashBudgets.get(1L));
    }
    @Test void depletedSymbolReceivesOnlyAnExistingBudgetReserve() {
        var b=book(1000000);b.accounts.put(1L,new Account(1,1000000,true));
        b.stocks.put(2L,new Stock(2,"B",10000,10000,10000,10000,true));
        b.lpCashBudgets.put(1L,0L);b.lpCashBudgets.put(2L,500000L);
        long before=b.lpCashBudgets.values().stream().mapToLong(Long::longValue).sum();
        long moved=b.rebalanceLiquidityCash(1,Map.of(1L,100000L,2L,100000L));
        assertEquals(100000,moved);
        assertEquals(100000,b.lpCashBudgets.get(1L));
        assertEquals(400000,b.lpCashBudgets.get(2L));
        assertEquals(before,b.lpCashBudgets.values().stream().mapToLong(Long::longValue).sum());
    }
    @Test void automatedSellerCannotConsumeLpBidBeforeHumanSeller() {
        var b=book(1000000);b.accounts.put(1L,new Account(1,1000000,true));
        b.accounts.put(4L,new Account(4,1000000,false,true));
        b.holdings.put(new PositionKey(4,1),new Holding(100,100,10000,0));
        b.lpCashBudgets.put(1L,50000L);
        b.submit(1,1,"BUY","LIMIT",2,10000,1000,4000);
        var automated=b.submit(4,1,"SELL","MARKET",1,0,1001,0);
        assertEquals("CANCELLED",automated.status);assertTrue(b.fills.isEmpty());
        b.submit(2,1,"SELL","MARKET",1,0,1002,0);
        assertEquals(1,b.fills.size());assertEquals(1,b.holding(1,1).quantity());
    }
    @Test void fundedLiquidityProviderAbsorbsBotSellingWhileKeepingItsHumanReserve() {
        var b=book(5_000_000);b.accounts.put(1L,new Account(1,5_000_000,true));
        b.accounts.put(4L,new Account(4,0,false,true));
        b.holdings.put(new PositionKey(4,1),new Holding(10,10,10000,0));
        b.lpCashBudgets.put(1L,5_000_000L);
        b.submit(1,1,"BUY","LIMIT",2,10000,1000,4000);
        var sold=b.submit(4,1,"SELL","MARKET",1,0,1001,0);
        assertEquals("FILLED",sold.status);assertEquals(1,b.fills.size());
        assertTrue(b.lpCashBudgets.get(1L)>=MarketMakerEngine.emergencyCashRequirement(12000));
        assertEquals(9,b.holding(4,1).quantity());assertEquals(1,b.holding(1,1).quantity());
    }
    @Test void depletedInlineLiquidityInventoryRestoresOnceAndPreservesRealizedProfit() {
        var b=book(1000000);
        b.restoreLiquidityInventory(2,1,400,10050);
        assertEquals(100,b.holding(2,1).quantity());

        var key=new PositionKey(2,1);
        b.holdings.put(key,new Holding(0,0,0,77));
        b.restoreLiquidityInventory(2,1,400,10050);
        assertEquals(new Holding(400,400,10050,77),b.holding(2,1));
        assertTrue(b.changedHoldings.contains(key));

        b.restoreLiquidityInventory(2,1,400,11000);
        assertEquals(new Holding(400,400,10050,77),b.holding(2,1));
    }
}
