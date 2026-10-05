package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.gamestock.backend.market.BatchOrderBook.*;
import static org.junit.jupiter.api.Assertions.*;

/** Event-time matching with funded accounts; no database, network, or application credentials. */
class MarketCapSimulationTest {
    record Result(long low,long high,long last,long fills,long fees) {}

    @Test void quietLargeCapsStayNearTheDailyReferenceAcrossDifferentSeeds() {
        int seconds=Integer.getInteger("market.cap.simulation.seconds",3600);
        for(long seed:List.of(7L,91L)) {
            String code=seed==7?"GI":"UMA";
            Result result=simulate(code,seed,seconds,0);
            System.out.printf(Locale.ROOT,"CAP_SIM code=%s seed=%d seconds=%d low=%d high=%d last=%d fills=%d%n",
                    code,seed,seconds,result.low(),result.high(),result.last(),result.fills());
            assertTrue(result.fills()>20,"A quiet stock must still have funded trades");
            assertTrue(result.low()>=9670 && result.high()<=10330,"ordinary daily range: "+result);
        }
    }

    @Test void realNewsCanMoveALargeCapBeyondItsOrdinaryRange() {
        Result result=simulate("GI",7,1800,.08);
        System.out.println("CAP_NEWS "+result);
        assertTrue(result.high()>10300,"News should move the valuation, not hit a permanent 3% wall");
    }

    @Test void productionBatchCadenceKeepsLargeCapsLiquidAndStable() {
        Result result=simulate("GI",91,600,0,100);
        assertTrue(result.fills()>20);
        assertTrue(result.low()>=9670 && result.high()<=10330,result.toString());
    }

    @Test void speculativeFlowCanDoubleAStockWithoutNewsOrDirectPriceWrites() {
        Result result=simulate("SD",7,3600,0);
        System.out.println("CAP_SPECULATIVE "+result);
        assertTrue(result.high()>=20000,"A low-cap rally must be possible through the participant engine: "+result);
        assertTrue(result.high()<=30000 && result.low()>=2000,"respect the fixed daily range");
    }

    static Result simulate(String code,long seed,int seconds,double news) {
        return simulate(code,seed,seconds,news,1000);
    }

    static Result simulate(String code,long seed,int seconds,double news,int cadence) {
        var profiles=BotProfile.activePopulation(seed,6020);
        long lp=100000;
        var accounts=new HashMap<Long,Account>();
        accounts.put(lp,new Account(lp,6_666_666,true,true));
        var participants=new ArrayList<BotActivityEngine.Participant>();
        // The same round-robin symbol allocation as the production batch scheduler.
        for(int i=4;i<profiles.size();i+=15) {
            long id=i+1;
            accounts.put(id,new Account(id,1_000_000,false,true));
            participants.add(new BotActivityEngine.Participant(id,profiles.get(i),1));
        }
        var symbol=new Stock(1,code,10000,10000,10000,10000,true);
        var stocks=new HashMap<Long,Stock>();stocks.put(1L,symbol);
        int target=StockMarketProfile.initialLiquidityInventory(code,100);
        var holdings=new HashMap<PositionKey,Holding>();
        holdings.put(new PositionKey(lp,1),new Holding(target,target,10000,0));
        long initialCash=accounts.values().stream().mapToLong(a->a.cash).sum();
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,1_000_000);
        book.lpCashBudgets.put(1L,6_666_666L);
        var engine=new BotActivityEngine(participants,seed,300);
        var patterns=new PatternEngine(seed^11);
        var regimes=new MarketRegimeEngine(seed^17);
        var history=new ArrayList<PriceMetricService.Trade>();
        var risk=new MarketMakerEngine.RiskBook(0,target,target,Math.max(1200,target*4),Math.max(1200,target*4)*10000L);
        long low=10000,high=10000,fills=0,fees=0;
        for(long elapsed=0;elapsed<seconds*1000L;elapsed+=cadence) {
            long now=1_000_000+elapsed;
            book.beginBatch(now);
            book.restoreLiquidityInventory(lp,1,target,symbol.last);
            history.removeIf(t->t.time()<now-600_000);
            double[] depth=book.depth(1);
            var metrics=PriceMetricService.calculate(history,symbol.last,depth[0],depth[1],(long)depth[2],(long)depth[3],now);
            regimes.advance(now,Map.of(code,"RPG"));
            var environment=StockMarketProfile.applyTo(code,regimes.environment(code,"RPG").combine(patterns.environment(code,now)));
            var observation=new BotActivityEngine.Observation(metrics,environment,10000,news,StockMarketProfile.liquidityScale(code,.65));
            engine.microBatches(book,Map.of(1L,observation),Map.of(),now,cadence,Map.of(1L,risk),lp,300);
            if(!book.fills.isEmpty()) {
                long volume=0;double notional=0;long top=0,bottom=Long.MAX_VALUE;
                for(var fill:book.fills) {
                    volume+=fill.quantity();notional+=fill.price()*fill.quantity();
                    top=Math.max(top,fill.price());bottom=Math.min(bottom,fill.price());
                    fees+=fill.buyerFee()+fill.sellerFee();
                }
                history.add(new PriceMetricService.Trade(now,symbol.last,volume,notional,top,bottom));
                low=Math.min(low,bottom);high=Math.max(high,top);fills+=book.fills.size();
            }
        }
        long reserved=accounts.keySet().stream().flatMap(id->book.working(id,1).stream()).mapToLong(o->o.reservedCash).sum();
        assertEquals(initialCash,accounts.values().stream().mapToLong(a->a.cash).sum()+reserved+fees,"cash plus collected fees must be conserved");
        assertTrue(accounts.values().stream().allMatch(a->a.cash>=0));
        assertTrue(book.holdings.values().stream().allMatch(h->h.quantity()>=0));
        return new Result(low,high,symbol.last,fills,fees);
    }
}
