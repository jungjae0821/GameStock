package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.gamestock.backend.market.BatchOrderBook.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real funded matching, calendar rollovers and the production population; never assign a price path. */
class BotMarketBalanceTest {
    record Result(List<Long> closes,long fills,long maxOpen,long longestQuiet,int distinct,long fees,long shares,long low) {}

    @Test void repeatedCalendarSessionsStayLiquidWithoutWalkingDownDailyFloors() {
        for(String code:System.getProperty("bot.balance.symbols","TR,ES,AK,GI").split(",")) {
            Result result=run(code,code.hashCode(),0,1000);
            System.out.println("BALANCE "+code+" "+result);
            assertTrue(result.fills()>600,"Continuous funded activity: "+result);
            assertTrue(result.maxOpen()<5000,"No accumulating sell wall: "+result);
            assertTrue(result.longestQuiet()<120_000,"No prolonged trading stall: "+result);
            assertTrue(result.distinct()>8,"Actual price discovery: "+result);
            long reference=10000;
            for(long close:result.closes()) {
                assertTrue(close>reference*.82,"A neutral session must not repeatedly finish on the bot floor: "+result);
                reference=close;
            }
            assertEquals(0,result.fees(),"Bot-only turnover preserves purchasing power");
        }
    }

    @Test void positiveDemandCanCloseUpAndKeepTradingAcrossKoreanMidnight() {
        Result result=run("ES",91,1,100);
        System.out.println("OVERNIGHT_BULL "+result);
        assertTrue(result.closes().get(0)>10000,"Positive valuation must be expressible as paid rising trades");
        assertTrue(result.closes().get(1)>10000,"Midnight must not reset the investment thesis or price");
        assertTrue(result.fills()>600);
        assertTrue(result.longestQuiet()<120_000);
        assertTrue(result.maxOpen()<5000);
    }

    @Test void adverseValuationCanStillProduceFallingExecutedPrices() {
        Result result=run("ES",91,-1,1000,true);
        Result neutral=run("ES",91,0,1000,true);
        System.out.println("OVERNIGHT_BEAR "+result);
        assertTrue(result.low()<10000,"Adverse value must permit actual downward executions");
        assertTrue(result.closes().stream().mapToLong(Long::longValue).average().orElseThrow()
                <neutral.closes().stream().mapToLong(Long::longValue).average().orElseThrow(),
                "Adverse value must lower session closes relative to the same seeded neutral market");
        assertTrue(result.fills()>600);
        assertTrue(result.distinct()>8);
    }

    private Result run(String code,long seed,int direction,int cadence) {
        return run(code,seed,direction,cadence,false);
    }

    private Result run(String code,long seed,int direction,int cadence,boolean establishedHoldings) {
        boolean bullish=direction>0;
        var profiles=BotProfile.activePopulation(seed,6020);
        long lp=100000;
        var accounts=new HashMap<Long,Account>();
        accounts.put(lp,new Account(lp,6_666_666,true,true));
        var participants=new ArrayList<BotActivityEngine.Participant>();
        for(int i=4;i<profiles.size();i+=15) {
            long id=i+1;accounts.put(id,new Account(id,1_000_000,false,true));
            participants.add(new BotActivityEngine.Participant(id,profiles.get(i),1));
        }
        int target=StockMarketProfile.initialLiquidityInventory(code,100);
        var holdings=new HashMap<PositionKey,Holding>();
        holdings.put(new PositionKey(lp,1),new Holding(target,target,10000,0));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,code,10000,10000,10000,10000,true));
        // A negative-value comparison needs holders able to sell: shorting is prohibited.
        // Give both paired scenarios identical paid initial inventory, rather than relying
        // on a particular strategy ordering to buy overpriced shares before it can sell.
        if(establishedHoldings) {
            for(var participant:participants) {
                accounts.get(participant.id()).cash-=10000;
                accounts.get(lp).cash+=10000;
                holdings.put(new PositionKey(participant.id(),1),new Holding(1,1,10000,0));
            }
            stocks.get(1L).circulatingShares=target+participants.size();
        }
        long start=ZonedDateTime.of(2026,10,18,23,55,0,0,ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,start);
        book.lpCashBudgets.put(1L,6_666_666L);
        long initialCash=accounts.values().stream().mapToLong(a->a.cash).sum();
        var engine=new BotActivityEngine(participants,seed,300);
        var patterns=new PatternEngine(seed^11);var regimes=new MarketRegimeEngine(seed^17);
        var history=new ArrayList<PriceMetricService.Trade>();
        var risk=new MarketMakerEngine.RiskBook(0,target,target,Math.max(1200,target*4),Math.max(1200,target*4)*10000L);
        var closes=new ArrayList<Long>();var prices=new HashSet<Long>();
        long fills=0,fees=0,maxOpen=0,longestQuiet=0;
        // Defaults to ten-minute active windows straddling midnight; extend with
        // -Dbot.balance.sessionMillis=21600000 for six hours per day. Idle gaps create no backlog.
        long sessionMillis=Long.getLong("bot.balance.sessionMillis",600_000L);
        for(int day=0;day<3;day++) {
            engine.resume();long quiet=0;
            long afterMidnightFills=0;
            for(long elapsed=0;elapsed<sessionMillis;elapsed+=cadence) {
                long now=start+day*86_400_000L+elapsed;
                book.beginBatch(now);
                Stock symbol=stocks.get(1L);
                if(elapsed==300_000) {
                    closes.add(symbol.last);
                    Stock next=new Stock(1,code,symbol.last,symbol.last,symbol.last,symbol.last,true);
                    next.circulatingShares=symbol.circulatingShares;stocks.put(1L,next);symbol=next;
                }
                book.restoreLiquidityInventory(lp,1,target,symbol.last);
                history.removeIf(t->t.time()<now-600_000);
                double[] depth=book.depth(1);
                var metrics=PriceMetricService.calculate(history,symbol.last,depth[0],depth[1],(long)depth[2],(long)depth[3],now);
                regimes.advance(now,Map.of(code,"RPG"));
                MarketEnvironment raw=regimes.environment(code,"RPG").combine(patterns.environment(code,now));
                if(direction!=0)raw=new MarketEnvironment(direction*.6,.8,1,bullish?1.4:1,bullish?1:1.4,1,1,.3,.2,.1,1,1);
                var environment=StockMarketProfile.applyTo(code,raw);
                var observation=new BotActivityEngine.Observation(metrics,environment,10000+direction*800,0,StockMarketProfile.liquidityScale(code,.65));
                engine.microBatches(book,Map.of(1L,observation),Map.of(),now,cadence,Map.of(1L,risk),lp,300);
                long open=0;
                for(long id:accounts.keySet()) {
                    List<Order> working=book.working(id,1);
                    if(id!=lp)assertTrue(working.size()<=1,"A participant has at most one working order");
                    for(Order order:working) {
                        open+=order.remaining;
                        if(id!=lp)assertTrue(order.remaining<=BotProfile.MAX_ORDER_SIZE);
                    }
                }
                maxOpen=Math.max(maxOpen,open);
                if(book.fills.isEmpty())quiet+=cadence;
                else {
                    quiet=0;long volume=0,top=0,bottom=Long.MAX_VALUE;double notional=0;
                    for(Fill fill:book.fills) {
                        volume+=fill.quantity();notional+=(double)fill.price()*fill.quantity();
                        top=Math.max(top,fill.price());bottom=Math.min(bottom,fill.price());
                        fees+=fill.buyerFee()+fill.sellerFee();prices.add(fill.price());
                    }
                    fills+=book.fills.size();
                    if(elapsed>=300_000)afterMidnightFills+=book.fills.size();
                    history.add(new PriceMetricService.Trade(now,symbol.last,volume,notional,top,bottom));
                }
                longestQuiet=Math.max(longestQuiet,quiet);
            }
            assertTrue(afterMidnightFills>100,"Trading must continue after every midnight");
            if(bullish)assertTrue(stocks.get(1L).last>10000,"Positive investment thesis survives midnight");
        }
        long reserved=accounts.keySet().stream().flatMap(id->book.working(id,1).stream()).mapToLong(o->o.reservedCash).sum();
        assertEquals(initialCash,accounts.values().stream().mapToLong(a->a.cash).sum()+reserved+fees);
        assertTrue(accounts.values().stream().allMatch(a->a.cash>=0));
        long shares=holdings.values().stream().mapToLong(Holding::quantity).sum();
        assertTrue(shares<=Math.max(512,target*4));
        assertEquals(shares,stocks.get(1L).circulatingShares);
        return new Result(closes,fills,maxOpen,longestQuiet,prices.size(),fees,shares,
                prices.stream().mapToLong(Long::longValue).min().orElseThrow());
    }
}
