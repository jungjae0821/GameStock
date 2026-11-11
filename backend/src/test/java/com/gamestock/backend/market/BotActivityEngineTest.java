package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.BatchOrderBook.*;

class BotActivityEngineTest {
    @Test void largeCapLpUsesPassiveSkewForSmallInventoryChangesButStillReducesLargeRisk() {
        for(int extra:List.of(20,200)) {
            var accounts=new HashMap<Long,Account>();
            accounts.put(1L,new Account(1,0,false,true));
            accounts.put(2L,new Account(2,5000000,true,true));
            accounts.put(3L,new Account(3,1000000,false,false));
            var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"UMA",10000,10000,10000,10000,true));
            var holdings=new HashMap<PositionKey,Holding>();
            holdings.put(new PositionKey(2,1),new Holding(500+extra,500+extra,10000,0));
            var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,10000);
            book.lpCashBudgets.put(1L,5000000L);
            book.submit(3,1,"BUY","LIMIT",20,10000,10000,100000);
            var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,BotProfile.defaults(1).get(2),1)),1,300);
            engine.act(book,Map.of(1L,observation()),Map.of(1L,10000),10000,0,
                    Map.of(1L,new MarketMakerEngine.RiskBook(5000000,500+extra,500,2000,20000000)),2);
            assertEquals(extra>500/3,!book.fills.isEmpty(),"Small deviations must not trigger a reversing aggressive trade");
        }
    }

    @Test void stalledEngineTrimsParticipantConcentrationAndKeepsHumanOrdersAndTwoSidedLpQuotes() {
        var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(3,BotProfile.defaults(1).get(2),1)),1,300);
        var accounts=new HashMap<Long,Account>();
        accounts.put(1L,new Account(1,5000000,true,true));
        accounts.put(2L,new Account(2,5000000,false,false));
        accounts.put(3L,new Account(3,0,false,true));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"ES",10000,10000,10000,10000,true));
        var holdings=new HashMap<PositionKey,Holding>();
        holdings.put(new PositionKey(1,1),new Holding(1000,1000,10000,0));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,0);
        book.lpCashBudgets.put(1L,5000000L);
        var human=book.submit(2,1,"BUY","LIMIT",50,10000,0,120000);
        for(long id=4;id<34;id++) {
            accounts.put(id,new Account(id,200000,false,true));
            book.submit(id,1,"BUY","LIMIT",10,10000,id,120000);
        }
        var risks=Map.of(1L,new MarketMakerEngine.RiskBook(5000000,1000,1000,2000,20000000));
        engine.act(book,Map.of(1L,observation()),Map.of(1L,10000),0,0,risks,1);
        assertEquals(300,book.participantQuantityAt(1,"BUY",10000));
        book.beginBatch(60000);
        engine.act(book,Map.of(1L,observation()),Map.of(1L,10000),60000,0,risks,1);
        assertEquals(100,book.participantQuantityAt(1,"BUY",10000));
        assertEquals("OPEN",human.status);assertEquals(50,human.remaining);
        assertTrue(book.fills.isEmpty());assertEquals(10000,stocks.get(1L).last);
        assertTrue(book.working(1,1).stream().anyMatch(o->o.side.equals("BUY")));
        assertTrue(book.working(1,1).stream().anyMatch(o->o.side.equals("SELL")));
        assertTrue(book.accepted.size()<=200);
    }

    @Test void nearbyThinQuotesRefillBeforeDeadlineEvenWithLargeRemoteWalls() {
        for(String code:List.of("UMA","BA","GOV","ZZZ","GI","SR","AK","WH","CRK","VR","ES","KRP","OW","PW","TR")) {
            var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,BotProfile.defaults(1).get(2),1)),1,300);
            var accounts=new HashMap<Long,Account>();
            accounts.put(1L,new Account(1,0,false,true));
            accounts.put(2L,new Account(2,5000000,true,true));
            accounts.put(3L,new Account(3,10000000,false,false));
            var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,code,10000,10000,10000,10000,true));
            var holdings=new HashMap<PositionKey,Holding>();
            holdings.put(new PositionKey(2,1),new Holding(1000,1000,10000,0));
            holdings.put(new PositionKey(3,1),new Holding(1000,1000,10000,0));
            var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,10000);
            book.lpCashBudgets.put(1L,5000000L);
            book.submit(3,1,"BUY","LIMIT",200,9000,10000,100000);
            book.submit(3,1,"SELL","LIMIT",200,11000,10000,100000);
            var risks=Map.of(1L,new MarketMakerEngine.RiskBook(0,1000,1000,2000,20000000));
            engine.act(book,Map.of(1L,observation()),Map.of(1L,10000),10000,0,risks,2);
            for(var order:book.working(2,1)) {
                int quantity=order.remaining-2;
                book.submit(3,1,order.side.equals("BUY")?"SELL":"BUY","LIMIT",quantity,order.price,10001,1000);
            }
            int fills=book.fills.size();long last=stocks.get(1L).last;
            assertEquals(16,book.working(2,1).stream().mapToInt(o->o.remaining).sum());
            engine.act(book,Map.of(1L,observation()),Map.of(1L,10000),10002,0,risks,2);
            for(String side:List.of("BUY","SELL"))assertTrue(book.working(2,1).stream()
                    .filter(o->o.side.equals(side)).mapToInt(o->o.remaining).sum()>=40,"Thin "+side+": "+code);
            assertEquals(fills,book.fills.size(),"Replenishment must remain passive: "+code);
            assertEquals(last,stocks.get(1L).last);
            long reserved=accounts.keySet().stream().flatMap(id->book.working(id,1).stream()).mapToLong(o->o.reservedCash).sum();
            long fees=book.fills.stream().mapToLong(f->f.buyerFee()+f.sellerFee()).sum();
            assertEquals(15000000,accounts.values().stream().mapToLong(a->a.cash).sum()+reserved+fees);
            assertEquals(2000,holdings.values().stream().mapToLong(Holding::quantity).sum());
        }
    }

    @Test void unusedLiquidityRepairSlotsRemainAvailableToFundedParticipants() {
        var accounts=new HashMap<Long,Account>();
        var participants=new ArrayList<BotActivityEngine.Participant>();
        for(long id=1;id<=300;id++) {
            accounts.put(id,new Account(id,1_000_000,false,true));
            participants.add(new BotActivityEngine.Participant(id,
                    BotProfile.create("bot_"+id,BotProfile.Strategy.AGGRESSIVE_VALUE,new Random(id)),1));
        }
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"A",10000,10000,10000,10000,true));
        var book=new BatchOrderBook(accounts,stocks,new HashMap<>(),List.of(),0,10000);
        var base=observation();
        var strongValue=new BotActivityEngine.Observation(base.metrics(),base.environment(),20000,0,.65);
        new BotActivityEngine(participants,1,300).act(book,Map.of(1L,strongValue),Map.of(),10000,500,Map.of(),-1);
        assertEquals(200,book.accepted.size(),"A nonexistent LP must not withhold eight participant slots");
        assertTrue(book.fills.isEmpty());
        long reserved=book.accepted.stream().mapToLong(o->o.reservedCash).sum();
        assertEquals(300_000_000,accounts.values().stream().mapToLong(a->a.cash).sum()+reserved);
    }

    @Test void pausedTradersLeaveLiquidityQuotesAndARampOnlyLetsAShareOfTradersAct() {
        int[] orders=new int[3];double[] shares={0,.1,1};
        for(int run=0;run<3;run++) {
            var accounts=new HashMap<Long,Account>();
            var participants=new ArrayList<BotActivityEngine.Participant>();
            accounts.put(1000L,new Account(1000,5_000_000,true,true));
            for(long id=1;id<=300;id++) {
                accounts.put(id,new Account(id,1_000_000,false,true));
                participants.add(new BotActivityEngine.Participant(id,
                        BotProfile.create("bot_"+id,BotProfile.Strategy.AGGRESSIVE_VALUE,new Random(id)),1));
            }
            var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"A",10000,10000,10000,10000,true));
            var holdings=new HashMap<PositionKey,Holding>();holdings.put(new PositionKey(1000,1),new Holding(1000,1000,10000,0));
            var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,10000);
            book.lpCashBudgets.put(1L,5_000_000L);
            var base=observation();
            var strongValue=new BotActivityEngine.Observation(base.metrics(),base.environment(),20000,0,.65);
            var engine=new BotActivityEngine(participants,1,300);engine.traderShare(shares[run]);
            engine.act(book,Map.of(1L,strongValue),Map.of(),10000,500,Map.of(1L,new MarketMakerEngine.RiskBook(0,1000,1000,2000,20_000_000)),1000);
            orders[run]=(int)book.accepted.stream().filter(o->o.user!=1000).count();
            assertTrue(book.working(1000,1).stream().anyMatch(o->o.side.equals("BUY")),"LP keeps quoting at share "+shares[run]);
            assertTrue(book.working(1000,1).stream().anyMatch(o->o.side.equals("SELL")),"LP keeps quoting at share "+shares[run]);
        }
        assertEquals(0,orders[0],"Paused traders must not place orders");
        assertTrue(orders[1]>0&&orders[1]<orders[2]/3,"Ramp must thin trader orders: "+Arrays.toString(orders));
    }

    @Test void traderRampGrowsLinearlyFromASmallStartToFullActivity() {
        assertEquals(1,AdminRuntimeControl.traderShare(null,0L));
        assertEquals(1,AdminRuntimeControl.traderShare(0,0L));
        assertEquals(1,AdminRuntimeControl.traderShare(300,null));
        assertEquals(AdminRuntimeControl.RAMP_START_SHARE,AdminRuntimeControl.traderShare(300,0L));
        assertEquals(.5,AdminRuntimeControl.traderShare(300,150_000L),1e-9);
        assertEquals(1,AdminRuntimeControl.traderShare(300,400_000L));
    }

    @Test void humanDrainedQuotesRefillOnTheNextBatchWithoutWaitingForTheRefreshDeadlineForEverySymbol() {
        for(String code:List.of("UMA","BA","GOV","ZZZ","GI","SR","AK","WH","CRK","VR","ES","KRP","OW","PW","TR")) {
            var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,BotProfile.defaults(1).get(2),1)),1,300);
            var accounts=new HashMap<Long,Account>();
            accounts.put(1L,new Account(1,0,false,true));
            accounts.put(2L,new Account(2,5_000_000,true,true));
            accounts.put(3L,new Account(3,10_000_000,false,false));
            var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,code,10000,10000,10000,10000,true));
            var holdings=new HashMap<PositionKey,Holding>();
            holdings.put(new PositionKey(2,1),new Holding(1000,1000,10000,0));
            holdings.put(new PositionKey(3,1),new Holding(1000,1000,10000,0));
            var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,10000);
            book.lpCashBudgets.put(1L,5_000_000L);
            var risk=Map.of(1L,new MarketMakerEngine.RiskBook(0,1000,1000,2000,20_000_000));
            var observations=Map.of(1L,observation());
            engine.act(book,observations,Map.of(1L,10000),10000,0,risk,2);
            int asks=(int)book.depth(1)[3];
            assertTrue(asks>=10,code);
            assertEquals("FILLED",book.submit(3,1,"BUY","MARKET",asks,0,10001,0).status);
            assertEquals(0,book.depth(1)[3],code);
            int trades=book.fills.size();
            engine.act(book,observations,Map.of(1L,10000),10001,0,risk,2);
            assertTrue(book.depth(1)[3]>=10,"Ask side must refill at +1ms, before the periodic deadline: "+code);
            assertEquals(trades,book.fills.size(),"Refilling must not manufacture a fill: "+code);
            int bids=(int)book.depth(1)[2];
            assertTrue(bids>=10,code);
            assertEquals("FILLED",book.submit(3,1,"SELL","MARKET",bids,0,10002,0).status);
            assertEquals(0,book.depth(1)[2],code);
            engine.act(book,observations,Map.of(1L,10000),10002,0,risk,2);
            assertTrue(book.depth(1)[2]>=10,"Bid side must refill at +2ms: "+code);
            assertEquals(2000,book.holdings.values().stream().mapToLong(Holding::quantity).sum());
            long reserved=accounts.keySet().stream().flatMap(id->book.working(id,1).stream()).mapToLong(o->o.reservedCash).sum();
            long fees=book.fills.stream().mapToLong(f->f.buyerFee()+f.sellerFee()).sum();
            assertEquals(15_000_000,accounts.values().stream().mapToLong(a->a.cash).sum()+reserved+fees);
            stocks.get(1L).continuous=false;
            book.working(2,1).forEach(book::cancel);
            engine.act(book,observations,Map.of(),10003,0,risk,2);
            assertTrue(book.working(2,1).isEmpty(),"A trading halt must not trigger replenishment: "+code);
        }
    }

    @Test void liquidityRepairStaysInsideTheOrderLimitWhenLpCashComesFromThisBatchsSales() {
        var accounts=new HashMap<Long,Account>();
        var participants=new ArrayList<BotActivityEngine.Participant>();
        for(long id=1;id<=300;id++) {
            accounts.put(id,new Account(id,1_000_000,false,true));
            participants.add(new BotActivityEngine.Participant(id,
                    BotProfile.create("bot_"+id,BotProfile.Strategy.AGGRESSIVE_VALUE,new Random(id)),1));
        }
        long lp=1000;accounts.put(lp,new Account(lp,0,true,true));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"A",10000,10000,10000,10000,true));
        var holdings=new HashMap<PositionKey,Holding>();holdings.put(new PositionKey(lp,1),new Holding(48,48,10000,0));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,10000);
        book.lpCashBudgets.put(1L,0L);
        var base=observation();
        var strongValue=new BotActivityEngine.Observation(base.metrics(),base.environment(),20000,0,.65);
        var risk=new MarketMakerEngine.RiskBook(0,48,48,100,1_000_000);
        new BotActivityEngine(participants,1,300).act(book,Map.of(1L,strongValue),Map.of(),10000,500,Map.of(1L,risk),lp);
        assertFalse(book.fills.isEmpty());assertTrue(accounts.get(lp).cash>0);
        assertTrue(book.accepted.size()<=200,"Proceeds-funded replenishment also needs reserved order slots");
        assertTrue(book.depth(1)[2]>0,"Sale proceeds fund real replacement bids");
        assertEquals(0,book.holding(lp,1).quantity(),"Buyers can consume all 48 published LP shares");
        assertEquals(0,book.depth(1)[3],"A sold-out LP must not leave fictitious asks");
        assertEquals(48,book.holdings.values().stream().mapToLong(Holding::quantity).sum());
        long reserved=book.accepted.stream().mapToLong(o->o.reservedCash).sum();
        assertEquals(300_000_000,accounts.values().stream().mapToLong(a->a.cash).sum()+reserved);
    }

    @Test void aRecentlyEmptiedBookKeepsZeroDepthWithoutPassingZeroPricesToStrategies() {
        var metrics=PriceMetricService.calculate(List.of(new PriceMetricService.Trade(1000,10000,10)),10000,9990,10010,4,4,1001);
        var empty=BotActivityEngine.withBook(metrics,10000,new double[4]);
        assertTrue(empty.recentVolume()>0);
        assertEquals(0,empty.bidDepth());assertEquals(0,empty.askDepth());
        assertEquals(9990,empty.bestBid());assertEquals(10010,empty.bestAsk());
        var oneSide=BotActivityEngine.withBook(metrics,10000,new double[]{9980,0,5,0});
        assertEquals(9980,oneSide.bestBid());assertEquals(10010,oneSide.bestAsk());
        assertEquals(5,oneSide.bidDepth());assertEquals(0,oneSide.askDepth());
    }

    @Test void humanCanBuyAndSellWhileTheLiquidityEngineIsRunning() {
        var p=BotProfile.defaults(1).get(2);
        var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,p,1)),1,300);
        var accounts=new HashMap<Long,Account>();
        accounts.put(1L,new Account(1,0,false,true));
        accounts.put(2L,new Account(2,2_000_000,true,true));
        accounts.put(3L,new Account(3,1_000_000,false,false));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"ES",10000,10000,10000,10000,true));
        var holdings=new HashMap<PositionKey,Holding>();holdings.put(new PositionKey(2,1),new Holding(100,100,10000,0));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,1000);
        book.lpCashBudgets.put(1L,2_000_000L);
        engine.act(book,Map.of(1L,observation()),Map.of(),2000,100,
                Map.of(1L,new MarketMakerEngine.RiskBook(2_000_000,100,100,400,4_000_000)),2);
        assertEquals("FILLED",book.submit(3,1,"BUY","MARKET",1,0,2001,0).status);
        assertEquals("FILLED",book.submit(3,1,"SELL","MARKET",1,0,2002,0).status);
        assertEquals(0,book.holding(3,1).quantity());
        assertTrue(book.fills.stream().allMatch(f->f.buyerFee()>0 && f.sellerFee()>0));
    }

    @Test void fastDecisionsRetainStrategicHoldingHorizonsAndSmallOrders() {
        var profiles=BotProfile.activePopulation(91,100);
        for(var p:profiles.subList(20,profiles.size())) {
            assertEquals(BotProfile.MIN_ORDER_SIZE,p.minOrderSize());assertEquals(BotProfile.MAX_ORDER_SIZE,p.maxOrderSize());
            if(!BotStrategyEngine.hasInventoryDemand(p)) {
                assertTrue(p.holdingTimePreference()>=30_000);
                assertTrue(p.positionLimit()>=8);
                assertTrue(p.decisionInterval()<p.holdingTimePreference());
            }
        }
    }

    @Test void staleOversizedBotOrdersAreRefundedWithoutCancellingAHumanOrder() {
        var p=BotProfile.activePopulation(1,21).get(20);
        var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,p,1)),1,300);
        var accounts=new HashMap<Long,Account>();
        accounts.put(1L,new Account(1,10_000_000,false,true));
        accounts.put(2L,new Account(2,10_000_000,false,false));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"ES",10000,10000,10000,10000,true));
        var book=new BatchOrderBook(accounts,stocks,new HashMap<>(),List.of(),0,1000);
        var bot=book.submit(1,1,"BUY","LIMIT",100,8000,1000,100000);
        var human=book.submit(2,1,"BUY","LIMIT",100,8000,1000,100000);
        engine.act(book,Map.of(1L,observation()),Map.of(),40_000,100,Map.of(),-1);
        assertEquals("CANCELLED",bot.status);assertEquals("OPEN",human.status);
        assertTrue(book.working(1,1).size()<=1);
        assertTrue(book.working(1,1).stream().allMatch(o->o.remaining<=BotProfile.MAX_ORDER_SIZE));
        assertEquals(10_000_000,book.accounts.get(1L).cash+book.working(1,1).stream().mapToLong(o->o.reservedCash).sum());
    }

    @Test void scaledProfilesAreReproducibleAndAccountsStayUnique() {
        var a=BotProfile.activePopulation(91,6020);var b=BotProfile.activePopulation(91,6020);
        assertEquals(a,b);assertEquals(BotProfile.defaults(91),a.subList(0,20));
        assertEquals(6020,a.stream().map(BotProfile::username).distinct().count());
        assertEquals(6020,a.stream().map(BotProfile::nickname).distinct().count());
        assertEquals(60,a.stream().skip(20).filter(p->p.strategy().family()==BotProfile.Family.VALUE).findFirst().orElseThrow().positionLimit());
        assertNotEquals(a.get(20).aggression(),a.get(28).aggression());
        for(int symbol=0;symbol<15;symbol++) {
            Set<BotProfile.Family> families=new HashSet<>();
            for(int i=symbol;i<a.size();i+=15)families.add(a.get(i).strategy().family());
            assertEquals(Set.of(BotProfile.Family.values()),families,"Every symbol needs independent value and flow traders");
        }
    }
    @Test void strategyMixSurvivesSeventeenListingsAndOtherPopulationStrides() {
        for(long seed:List.of(7L,91L,20261024L)) {
            var profiles=BotProfile.activePopulation(seed,6020);
            for(int listings:List.of(15,16,17,18,20,34)) {
                for(int symbol=0;symbol<listings;symbol++) {
                    Set<BotProfile.Family> families=EnumSet.noneOf(BotProfile.Family.class);
                    for(int i=symbol;i<profiles.size();i+=listings)families.add(profiles.get(i).strategy().family());
                    assertEquals(Set.of(BotProfile.Family.values()),families,
                            "seed="+seed+" listings="+listings+" symbol="+symbol);
                }
            }
        }
    }

    @Test void aLegacyHoldingDoesNotShareItsDecisionTimerWithTheNewPrimaryStock() {
        var profile=BotProfile.defaults(91).stream().filter(p->p.strategy().family()==BotProfile.Family.NOISE).findFirst().orElseThrow();
        var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,profile,1,2),
                new BotActivityEngine.Participant(1,profile,2,2)),91,300);
        var accounts=new HashMap<Long,Account>();
        accounts.put(1L,new Account(1,1_000_000,false,true));
        accounts.put(2L,new Account(2,1_000_000,true,true));
        var stocks=new LinkedHashMap<Long,Stock>();
        stocks.put(1L,new Stock(1,"GI",10000,10000,10000,10000,true));
        stocks.put(2L,new Stock(2,"WH",10000,10000,10000,10000,true));
        var holdings=new HashMap<PositionKey,Holding>();
        for(long stock:List.of(1L,2L))holdings.put(new PositionKey(2,stock),new Holding(100,100,10000,0));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,1000);
        for(long stock:List.of(1L,2L)) {
            book.submit(2,stock,"BUY","LIMIT",50,9990,1000,120000);
            book.submit(2,stock,"SELL","LIMIT",50,10010,1000,120000);
        }
        Set<Long> traded=new HashSet<>();
        for(long now=1000;now<61000;now+=1000) {
            book.beginBatch(now);
            engine.act(book,Map.of(1L,observation(),2L,observation()),Map.of(),now,1000,Map.of(),-1);
            for(var fill:book.fills)traded.add(fill.buy().stock);
        }
        assertEquals(Set.of(1L,2L),traded,"Both watched positions must get independently scheduled decisions");
        assertTrue(accounts.values().stream().allMatch(a->a.cash>=0));
    }

    private BotActivityEngine.Observation observation() {
        var metric=PriceMetricService.calculate(List.of(new PriceMetricService.Trade(1000,10000,1)),10000,9990,10010,50,50,1000);
        return new BotActivityEngine.Observation(metric,MarketEnvironment.neutral(),10000,0,.65);
    }
    @Test void activityTargetCannotMintCashSharesTradesOrPrices() {
        var profiles=BotProfile.activePopulation(17,22);
        var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,profiles.get(20),1),new BotActivityEngine.Participant(2,profiles.get(21),1)),17,300);
        var accounts=new HashMap<Long,Account>();accounts.put(1L,new Account(1,0,false));accounts.put(2L,new Account(2,0,false));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"A",10000,10000,10000,10000,true));
        var book=new BatchOrderBook(accounts,stocks,new HashMap<>(),List.of(),0,1000);
        for(int i=0;i<100;i++)engine.act(book,Map.of(1L,observation()),Map.of(),1000+i*50,50,Map.of(),-1);
        assertTrue(book.fills.isEmpty());assertTrue(book.prices.isEmpty());assertTrue(book.holdings.isEmpty());
        assertEquals(10000,stocks.get(1L).last);assertEquals(0,accounts.values().stream().mapToLong(a->a.cash).sum());
    }
    @Test void lpRebalanceSellsOnlyExistingSharesAgainstFundedBids() {
        var profile=BotProfile.defaults(1).get(2);
        var engine=new BotActivityEngine(List.of(new BotActivityEngine.Participant(1,profile,1)),1,300);
        var accounts=new HashMap<Long,Account>();accounts.put(1L,new Account(1,1000000,false));accounts.put(2L,new Account(2,0,true));
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,new Stock(1,"A",10000,10000,10000,10000,true));
        var holdings=new HashMap<PositionKey,Holding>();holdings.put(new PositionKey(2,1),new Holding(400,400,10000,0));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,1000);
        book.submit(1,1,"BUY","LIMIT",20,10000,1000,10000);
        engine.act(book,Map.of(1L,observation()),Map.of(1L,100),2000,50,Map.of(1L,new MarketMakerEngine.RiskBook(0,400,100,1200,12000000)),2);
        assertEquals(395,book.holding(2,1).quantity());assertEquals(5,book.holding(1,1).quantity());
        assertEquals(49950,book.accounts.get(2L).cash);assertEquals(1,book.fills.size());
    }
}
