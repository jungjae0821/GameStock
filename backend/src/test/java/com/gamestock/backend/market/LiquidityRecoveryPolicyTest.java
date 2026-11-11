package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.BatchOrderBook.*;

class LiquidityRecoveryPolicyTest {
    private BatchOrderBook book() {
        var accounts=new HashMap<Long,Account>();
        accounts.put(1L,new Account(1,5000000,true,true));
        accounts.put(2L,new Account(2,5000000,false,false));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"ES",10000,10000,10000,10000,true));
        var holdings=new HashMap<PositionKey,Holding>();
        holdings.put(new PositionKey(1,1),new Holding(1000,1000,10000,0));
        holdings.put(new PositionKey(2,1),new Holding(1000,1000,10000,0));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,0);
        book.lpCashBudgets.put(1L,5000000L);return book;
    }
    private MarketMakerEngine.RiskBook risk() {return new MarketMakerEngine.RiskBook(5000000,1000,1000,2000,20000000);}

    @Test void ownLpQuotesDoNotCreateTheirOwnDemandSignal() {
        var book=book();
        book.submit(1,1,"BUY","LIMIT",300,9990,0,100000);
        book.submit(2,1,"SELL","LIMIT",30,10010,0,100000);
        assertEquals(-1,book.liquidityPressure(1,1));
        book.submit(2,1,"BUY","MARKET",20,0,1,0);
        // The same user's ask is not evidence of a buy execution.
        assertEquals(-1,book.liquidityPressure(1,1));
    }

    @Test void aggressiveExecutionsCanOutweighRestingBookPressure() {
        var book=book();
        book.submit(1,1,"SELL","LIMIT",100,10010,0,100000);
        book.submit(2,1,"BUY","MARKET",10,0,1,0);
        assertTrue(book.liquidityPressure(1,1)>0);
        book.submit(2,1,"SELL","LIMIT",200,10020,2,100000);
        assertTrue(book.liquidityPressure(1,1)>0,"Actual buying has 70% weight even when resting asks dominate");
    }

    @Test void plateauRequiresAFullMinuteAndRestartsAfterAPriceChangeOrResume() {
        var book=book();var policy=new LiquidityRecoveryPolicy();var stock=book.stocks.get(1L);
        assertFalse(policy.stalled(stock,0));assertFalse(policy.stalled(stock,59999));
        assertTrue(policy.stalled(stock,60000));stock.last=10010;
        assertFalse(policy.stalled(stock,60001));assertTrue(policy.stalled(stock,120001));
        policy.reset();assertFalse(policy.stalled(stock,180001));
    }

    @Test void trimmingRefundsNewerBotsAndDispersesNewOrdersWithoutTouchingHumans() {
        var book=book();var policy=new LiquidityRecoveryPolicy();
        var human=book.submit(2,1,"BUY","LIMIT",200,9900,0,100000);
        var oldest=new ArrayList<Order>();
        for(long id=3;id<33;id++) {
            book.accounts.put(id,new Account(id,200000,false,true));
            oldest.add(book.submit(id,1,"BUY","LIMIT",10,9900,id,100000));
        }
        long cash=book.accounts.values().stream().mapToLong(a->a.cash).sum()+reserved(book);
        policy.trimBotWalls(book,1);
        assertEquals(100,book.participantQuantityAt(1,"BUY",9900));
        assertEquals("OPEN",human.status);assertEquals(200,human.remaining);
        assertTrue(oldest.subList(0,10).stream().allMatch(o->o.status.equals("OPEN")));
        assertTrue(oldest.subList(10,30).stream().allMatch(o->o.status.equals("CANCELLED")));
        assertEquals(9890,policy.dispersedPrice(book,1,"BUY",9900,10,PriceLimitPolicy.botBand(10000)));
        assertEquals(0,policy.dispersedPrice(book,1,"BUY",9900,10,new PriceLimitPolicy.PriceBand(9900,9900,12000)));
        assertTrue(book.fills.isEmpty());assertEquals(10000,book.stocks.get(1L).last);
        assertEquals(cash,book.accounts.values().stream().mapToLong(a->a.cash).sum()+reserved(book));
    }

    @Test void humanWallsAreReducedByRealFundedFillsWithCooldownAndFees() {
        for(String side:List.of("BUY","SELL")) {
            var book=book();var policy=new LiquidityRecoveryPolicy();var stock=book.stocks.get(1L);
            var wall=book.submit(2,1,side,"LIMIT",200,10000,0,1000000);
            policy.stalled(stock,0);assertTrue(policy.stalled(stock,60000));
            assertTrue(policy.absorbWall(book,stock,risk(),1,10000,60000));
            assertEquals(200-BotProfile.MAX_ORDER_SIZE,wall.remaining);assertEquals(1,book.fills.size());
            assertEquals(side.equals("BUY")?1:-1,book.liquidityPressure(1,1),
                    "LP recovery executions must not generate their own demand signal");
            assertFalse(policy.absorbWall(book,stock,risk(),1,10000,60500));
            long fees=book.fills.stream().mapToLong(f->f.buyerFee()+f.sellerFee()).sum();
            assertEquals(10000000,book.accounts.values().stream().mapToLong(a->a.cash).sum()+reserved(book)+fees);
            assertEquals(2000,book.holdings.values().stream().mapToLong(Holding::quantity).sum());
            assertTrue(fees>0);
        }
    }

    @Test void recoveryRespectsBudgetInventoryFairValueAndTradingHalts() {
        var book=book();var policy=new LiquidityRecoveryPolicy();var stock=book.stocks.get(1L);
        book.submit(2,1,"SELL","LIMIT",200,10200,0,1000000);
        policy.stalled(stock,0);policy.stalled(stock,60000);
        assertFalse(policy.absorbWall(book,stock,risk(),1,10000,60000));assertTrue(book.fills.isEmpty());
        book.working(2,1).forEach(book::cancel);
        book.submit(2,1,"SELL","LIMIT",200,10000,0,1000000);
        book.accounts.get(1L).cash=0;book.lpCashBudgets.put(1L,0L);
        policy.absorbWall(book,stock,risk(),1,10000,61000);assertTrue(book.fills.isEmpty());
        book.accounts.get(1L).cash=5000000;book.lpCashBudgets.put(1L,5000000L);stock.continuous=false;
        assertFalse(policy.absorbWall(book,stock,risk(),1,10000,62000));assertTrue(book.fills.isEmpty());
    }

    @Test void stalledFlowUsesFundedParticipantAgainstExistingLpQuote() {
        var book=book();var policy=new LiquidityRecoveryPolicy();var stock=book.stocks.get(1L);
        book.accounts.put(3L,new Account(3,5000000,false,true));
        book.holdings.put(new PositionKey(3,1),new Holding(1000,1000,10000,0));
        book.submit(1,1,"SELL","LIMIT",20,10000,0,100000);
        book.submit(1,1,"BUY","LIMIT",20,9990,0,100000);
        policy.stalled(stock,0);assertTrue(policy.stalled(stock,60000));
        assertTrue(policy.submitStalledFlow(book,stock,List.of(3L),1L,60000));
        assertEquals(1,book.fills.size());
        var first=book.fills.get(0);
        assertEquals(3,first.taker().user);assertEquals(1,first.maker().user);
        assertFalse(policy.submitStalledFlow(book,stock,List.of(3L),1L,61000));
        assertEquals(1,book.fills.size());
        assertTrue(policy.submitStalledFlow(book,stock,List.of(3L),1L,65000));
        assertEquals(2,book.fills.size());
    }

    private long reserved(BatchOrderBook book) {
        return book.accounts.keySet().stream().flatMap(id->book.working(id,1).stream()).mapToLong(o->o.reservedCash).sum();
    }
}
