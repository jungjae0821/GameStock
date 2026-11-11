package com.gamestock.backend.market;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.*;
import java.util.stream.Stream;
import static com.gamestock.backend.market.BatchOrderBook.*;
import static org.junit.jupiter.api.Assertions.*;

class BotLiquidityRecoveryTest {
    private static final List<String> SYMBOLS=List.of("UMA","BA","GOV","ZZZ","GI","SR","AK","WH","CRK","VR","ES","KRP","OW","PW","TR");

    static Stream<Arguments> cases() {
        return java.util.stream.IntStream.range(0,SYMBOLS.size()).boxed()
                .flatMap(index->Stream.of(.5,.98,1.02,2.0).map(valuation->Arguments.of(SYMBOLS.get(index),index,valuation)));
    }

    @ParameterizedTest(name="{0}, stale valuation x{2}") @MethodSource("cases")
    void everySymbolRecoversWithDepletedLpAndLegacyInventoryDespiteStaleValuation(String code,int index,double valuation) {
        long lp=100000,start=1_000_000;
        var accounts=new HashMap<Long,Account>();
        accounts.put(lp,new Account(lp,6_666_666,true,true));
        var holdings=new HashMap<PositionKey,Holding>();
        var participants=new ArrayList<BotActivityEngine.Participant>();
        var profiles=BotProfile.activePopulation(91,6020);
        for(int i=index;i<profiles.size();i+=SYMBOLS.size()) {
            long id=i+1;
            accounts.put(id,new Account(id,1_000_000,false,true));
            // Legacy stock is concentrated in old accounts; other funded traders have no shares.
            if(i/SYMBOLS.size()<150)holdings.put(new PositionKey(id,1),new Holding(32,32,10000,0));
            participants.add(new BotActivityEngine.Participant(id,profiles.get(i),1));
        }
        var stocks=new HashMap<Long,Stock>();
        stocks.put(1L,new Stock(1,code,10000,10000,10000,10000,true));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,start);
        book.lpCashBudgets.put(1L,6_666_666L);
        long cash=accounts.values().stream().mapToLong(a->a.cash).sum();
        long shares=holdings.values().stream().mapToLong(Holding::quantity).sum();
        int target=StockMarketProfile.initialLiquidityInventory(code,100);
        assertTrue(shares>=Math.max(512,target*4));
        var risk=new MarketMakerEngine.RiskBook(0,0,target,Math.max(1200,target*4),Math.max(1200,target*4)*10000L);
        var engine=new BotActivityEngine(participants,91+index,300);
        var history=new ArrayList<PriceMetricService.Trade>();
        long fills=0,lateFills=0,lpPurchases=0,largerOrders=0;
        int twoSided=0;
        for(long elapsed=0;elapsed<180_000;elapsed+=1000) {
            long now=start+elapsed;
            book.beginBatch(now);
            book.restoreLiquidityInventory(lp,1,target,stocks.get(1L).last);
            var depth=book.depth(1);
            var metrics=PriceMetricService.calculate(history,stocks.get(1L).last,depth[0],depth[1],(long)depth[2],(long)depth[3],now);
            var observation=new BotActivityEngine.Observation(metrics,MarketEnvironment.neutral(),10000*valuation,0,StockMarketProfile.liquidityScale(code,.65));
            engine.microBatches(book,Map.of(1L,observation),Map.of(),now,1000,Map.of(1L,risk),lp,300);
            assertTrue(book.accepted.size()<=400,"Immediate replenishment must retain the two-pass order bound");
            for(var order:book.accepted)if(order.user!=lp) {
                assertTrue(order.quantity<=BotProfile.MAX_ORDER_SIZE);
                assertTrue(order.quantity>=1,"Allocation adjustments and funded recovery may use single shares");
                if(order.quantity>=BotProfile.MIN_ORDER_SIZE)largerOrders++;
            }
            fills+=book.fills.size();
            if(elapsed>=120_000)lateFills+=book.fills.size();
            for(var fill:book.fills) {
                if(fill.buy().user==lp)lpPurchases+=fill.quantity();
                history.add(new PriceMetricService.Trade(now,fill.price(),fill.quantity()));
            }
            depth=book.depth(1);
            if(depth[2]>0&&depth[3]>0)twoSided++;
            assertTrue(book.stocks.get(1L).continuous);
            assertTrue(book.fills.stream().allMatch(f->PriceLimitPolicy.botBand(10000).contains(f.price())));
            for(var participant:participants) {
                var working=book.working(participant.id(),1);
                assertTrue(working.size()<=1);
                assertTrue(working.stream().allMatch(o->o.remaining<=BotProfile.MAX_ORDER_SIZE));
            }
        }
        String context=code+" valuation="+valuation+" fills="+fills+" late="+lateFills+" twoSided="+twoSided+" lpPurchases="+lpPurchases
                +" last="+stocks.get(1L).last+" lpInventory="+book.holding(lp,1).quantity()+" depth="+Arrays.toString(book.depth(1));
        System.out.println("RECOVERY "+context);
        assertTrue(fills>100,context);
        assertTrue(largerOrders>100,"Recovery must use actual minimum-lot participant orders: "+context);
        // LP top-ups change bot turnover. Verify continued activity separately from human fillability.
        assertTrue(lateFills>0,"Trading must continue after recovery: "+context);
        boolean executableValue=valuation>=.8 && valuation<=1.2;
        if(executableValue) {
            assertTrue(twoSided>30,"Resting liquidity must survive ordinary valuation differences: "+context);
            // A competitive participant book may fill every trade before the backstop LP.
            // The funded human round-trip below verifies usable liquidity without forcing
            // executions to a particular counterparty against price-time priority.
        }
        // Fund a human from an existing participant; the probe must not add cash or shares.
        long human=100001;
        Account donor=accounts.values().stream().filter(a->!a.liquidityProvider)
                .max(Comparator.comparingLong(a->a.cash)).orElseThrow();
        long funding=200000;
        assertTrue(donor.cash>=funding,context);
        donor.change(-funding);accounts.put(human,new Account(human,funding,false,false));
        long probeAt=start+180000;
        var buy=book.submit(human,1,"BUY","MARKET",5,0,probeAt,0);
        if(executableValue)assertEquals("FILLED",buy.status,"An ordinary value gap retains human fillability: "+context);
        int acquired=book.holding(human,1).quantity();
        int unsold=0;
        if(acquired>0) {
            var executionBand=PriceLimitPolicy.marketExecutionBand(stocks.get(1L).last);
            long executableBids=book.resting(1,"BUY").stream()
                    .filter(o->o.user!=human && !o.market() && executionBand.contains(o.price))
                    .mapToLong(o->o.remaining).sum();
            var sale=book.submit(human,1,"SELL","MARKET",acquired,0,probeAt+1,0);
            unsold=(int)Math.max(0,acquired-executableBids);
            assertEquals(unsold,book.holding(human,1).quantity(),
                    "All displayed bids must execute; absent bids cannot be invented: "+context);
            if(unsold==0)assertEquals("FILLED",sale.status,context);
            else assertEquals("CANCELLED",sale.status,"Unfilled market remainder is cancelled: "+context);
        }
        // Finite LP cash may run out under one-sided selling, just as its shares can run
        // out under buying. Do not hide human-only reserves in publicly displayed quotes.
        assertEquals(unsold,book.holding(human,1).quantity());
        assertEquals(shares,holdings.values().stream().mapToLong(Holding::quantity).sum(),"No new shares: "+context);
        assertEquals(shares,stocks.get(1L).circulatingShares);
        long reserved=accounts.keySet().stream().flatMap(id->book.working(id,1).stream()).mapToLong(o->o.reservedCash).sum();
        long fees=book.fills.stream().mapToLong(f->f.buyerFee()+f.sellerFee()).sum();
        if(acquired>0)assertTrue(fees>0,"Human executions retain ordinary fees: "+context);
        assertEquals(cash,accounts.values().stream().mapToLong(a->a.cash).sum()+reserved+fees,"Cash conservation: "+context);
        assertTrue(accounts.values().stream().allMatch(a->a.cash>=0));
        assertTrue(holdings.values().stream().allMatch(h->h.quantity()>=0&&h.settled()>=0));
    }
}
