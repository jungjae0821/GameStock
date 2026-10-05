package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.BotProfile.*;

class SimulationEngineTest {
    private final MarketEnvironment neutral=MarketEnvironment.neutral();
    private PriceMetricService.Metrics history(double step) {
        List<PriceMetricService.Trade> trades=new ArrayList<>();
        for(int i=0;i<=60;i++) trades.add(new PriceMetricService.Trade(i*1000L,10000+i*step,20));
        double last=10000+60*step;
        return PriceMetricService.calculate(trades,last,last-10,last+10,100,100,60000);
    }
    private BotProfile profile(Strategy strategy) {return BotProfile.create("test_01",strategy,new Random(12));}
    private double score(Strategy strategy,PriceMetricService.Metrics metrics,int position,double estimate) {
        return new BotStrategyEngine(1).score(profile(strategy),metrics,neutral,
                new BotStrategyEngine.Position(position,1000000,0,0),estimate,0,0);
    }
    @Test void valueDirectionsAreCorrectForEverySubtype() {
        var m=history(0);
        for(Strategy s:Strategy.values()) if(s.family()==Family.VALUE) {
            assertTrue(score(s,m,0,11000)>score(s,m,0,10000),s.name());
            assertTrue(score(s,m,0,9000)<score(s,m,0,10000),s.name());
        }
    }
    @Test void momentumUsesObservedReturns() {
        for(Strategy s:Strategy.values()) if(s.family()==Family.MOMENTUM) {
            assertTrue(score(s,history(10),0,1)>score(s,history(-10),0,999999),s.name());
        }
    }
    @Test void contrarianFadesOverheatedPrices() {
        for(Strategy s:Strategy.values()) if(s.family()==Family.CONTRARIAN)
            assertTrue(score(s,history(10),0,10000)<score(s,history(-10),0,10000),s.name());
    }
    @Test void inventoryPenaltyIsMonotonicWithoutRandomReversal() {
        for(Strategy s:Strategy.values()) {
            double previous=Double.POSITIVE_INFINITY;
            for(int position:List.of(0,20,50,100)) {
                double current=score(s,history(5),position,11000);
                assertTrue(current<previous,s.name()); previous=current;
            }
        }
    }
    @Test void privateValueEstimatesDifferAndUpdateSlowly() {
        var engine=new BotStrategyEngine(7);
        List<Double> values=new ArrayList<>();
        for(BotProfile p:BotProfile.defaults(7)) if(p.strategy().family()==Family.VALUE) {
            double a=engine.estimate(p,"A",10000),b=engine.estimate(p,"A",12000);
            assertTrue(b>a); assertTrue(b<12000*(1+p.valueError())); values.add(a);
        }
        assertEquals(values.size(),new HashSet<>(values).size());
    }
    @Test void valueResearchDiffersAcrossSymbolsAndIsReproducible() {
        var a=new BotStrategyEngine(42); var b=new BotStrategyEngine(42);
        var p=profile(Strategy.DEEP_VALUE);
        Set<Double> estimates=new HashSet<>();
        for(int i=0;i<15;i++) {
            double value=a.estimate(p,"S"+i,10000);
            estimates.add(value); assertEquals(value,b.estimate(p,"S"+i,10000));
        }
        assertEquals(15,estimates.size());
    }
    @Test void restingOrdersKeepPriorityUntilTheirThesisOrPriceChangesMaterially() {
        var policy=new BotOrderPolicy(); var p=profile(Strategy.DEEP_VALUE);
        var buy=new BotOrderPolicy.RestingOrder(1,"BUY",9900,99099,0);
        assertFalse(policy.shouldReplace(p,buy,new BotStrategyEngine.Decision("BUY",1,.7,5,false,9910,90000),30000,10,9900));
        assertFalse(policy.shouldReplace(p,buy,new BotStrategyEngine.Decision("HOLD",.01,.1,0,false,10000,90000),60000,10,9950));
        assertTrue(policy.shouldReplace(p,buy,new BotStrategyEngine.Decision("SELL",-2,.9,0,false,9800,90000),30000,10,9900));
        assertTrue(policy.shouldReplace(p,buy,new BotStrategyEngine.Decision("BUY",1,.7,5,false,10000,90000),30000,10,9990));
        // A different random price proposal alone must not cancel even a fast bot's best bid.
        assertFalse(policy.shouldReplace(profile(Strategy.SCALPER),buy,
                new BotStrategyEngine.Decision("BUY",1,.7,5,false,9950,8000),5000,10,9900));
    }
    @Test void patientInvestorsUsuallyCompeteInsideTheSpread() {
        var engine=new BotStrategyEngine(17); var p=profile(Strategy.CONSERVATIVE_VALUE);
        var m=PriceMetricService.calculate(List.of(),10000,9700,10300,100,100,0);
        int restingInside=0;
        for(int i=0;i<1000;i++) {
            var d=engine.decide(p,m,neutral,new BotStrategyEngine.Position(0,1000000,0,0),10000*(1+p.entryThreshold()*1.2),0,10,1);
            if(!d.market() && d.side().equals("BUY") && d.limitPrice()>m.bestBid() && d.limitPrice()<m.bestAsk()) restingInside++;
        }
        assertTrue(restingInside>500,"Patient strategies should supply competitive liquidity");
    }
    @Test void affordableValueEdgeCrossesWithACappedLimitAndCanReplaceItsOwnBestBid() {
        var engine=new BotStrategyEngine(17); var p=profile(Strategy.CONSERVATIVE_VALUE);
        var m=PriceMetricService.calculate(List.of(),10000,9960,10040,100,100,0);
        assertTrue(engine.executableEdge(p,m,"BUY",10500)>0);
        assertTrue(engine.executableEdge(p,m,"BUY",10050)<0,"Gross edge must cover fees and a risk margin");
        assertTrue(engine.executableEdge(p,m,"SELL",9500)>0);
        for(int i=0;i<30;i++) {
            var d=engine.decide(p,m,neutral,new BotStrategyEngine.Position(0,1000000,0,0),10500,0,10,1);
            assertEquals("BUY",d.side()); assertFalse(d.market()); assertEquals(m.bestAsk(),d.limitPrice());
            assertTrue(new BotOrderPolicy().shouldReplace(p,new BotOrderPolicy.RestingOrder(1,"BUY",9960,99700,0),
                    d,30000,10,9960,10040));
        }
        var costly=PriceMetricService.calculate(List.of(),10000,9400,10600,100,100,0);
        assertTrue(engine.executableEdge(p,costly,"BUY",10500)<0,"An estimate below the ask is not an executable gain");
    }
    @Test void rotatingResearchChangesPartnersAndCannotStarveAnActionableAlternative() {
        for(int size:List.of(1,2,3,5,15,16)) {
            int cursor=0,count=Math.min(3,size); Set<Integer> starts=new HashSet<>();
            for(int i=0;i<size;i++) {starts.add(cursor);cursor=BotOpportunitySelector.nextCursor(cursor,count,size);}
            assertEquals(size,starts.size(),"Every symbol gets different comparison partners");
        }
        var selector=new BotOpportunitySelector();
        var candidates=List.of(new BotOpportunitySelector.Opportunity("expensive",1000,true),
                new BotOpportunitySelector.Opportunity("ES",1.01,true),
                new BotOpportunitySelector.Opportunity("unfunded",9999,false));
        int selected=0;
        for(int i=0;i<12;i++) {
            String result=selector.select("bot",candidates);
            assertNotEquals("unfunded",result);
            if(result.equals("ES")) selected++;
        }
        assertTrue(selected>=3,"An affordable above-threshold opportunity must get a turn");
    }
    @Test void quietHistoryRetainsLongHorizonEvidenceWithoutInventingRecentActivity() {
        var trades=List.of(new PriceMetricService.Trade(0,10000,100),new PriceMetricService.Trade(240000,10200,1));
        var m=PriceMetricService.calculate(trades,10200,10190,10210,100,100,360000);
        assertEquals(0,m.volume()); assertEquals(0,m.volatility()); assertEquals(0,m.return20s());
        assertEquals(10200,m.vwap()); assertTrue(m.referenceVwap()<10003);
        assertTrue(score(Strategy.VWAP_FADE,m,0,10200)<-.5);
        assertTrue(score(Strategy.SLOW_MOMENTUM,m,0,10200)>score(Strategy.SLOW_MOMENTUM,history(0),0,10000));
    }
    @Test void silencePromotesOnlyFundedIntentsAndMarketReplaceDoesNotDependOnUnusedLimitPrice() {
        var selector=new BotOpportunitySelector();
        assertEquals("quiet",selector.select("bot",List.of(
                new BotOpportunitySelector.Opportunity("active",2,true,0),
                new BotOpportunitySelector.Opportunity("quiet",1.1,true,90000),
                new BotOpportunitySelector.Opportunity("no_signal",100,false,900000))));
        var p=profile(Strategy.SCALPER);
        assertTrue(new BotOrderPolicy().shouldReplace(p,new BotOrderPolicy.RestingOrder(1,"BUY",9900,99099,0),
                new BotStrategyEngine.Decision("BUY",1,.7,1,true,9900,8000),5000,10,9900,9910));
    }
    @Test void neutralIntradaySignalsSupplySmallFundedQuotesWithFeesCovered() {
        var engine=new BotStrategyEngine(48);
        var m=PriceMetricService.calculate(List.of(),10000,9950,10050,100,100,0);
        int buys=0,sells=0;
        for(Strategy strategy:List.of(Strategy.SCALPER,Strategy.VWAP_SCALPER,Strategy.MICRO_TREND,Strategy.BOOK_IMBALANCE)) {
            var p=profile(strategy);
            for(int i=0;i<30;i++) {
                var buy=engine.decide(p,m,neutral,new BotStrategyEngine.Position(0,1000000,0,0),10000,0,10,1);
                if(Math.abs(buy.score())<=engine.threshold(p,m)) {
                    buys++; assertEquals("BUY",buy.side()); assertFalse(buy.market());
                    assertTrue(buy.limitPrice()<m.bestAsk()); assertTrue(buy.limitPrice()<10000/1.001);
                    assertTrue(buy.quantity()>0 && buy.quantity()<=2);
                }
                var sell=engine.decide(p,m,neutral,new BotStrategyEngine.Position(1,1000000,10000,0),10000,0,10,1);
                if(Math.abs(sell.score())<=engine.threshold(p,m)) {
                    sells++; assertEquals("SELL",sell.side()); assertFalse(sell.market());
                    assertTrue(sell.limitPrice()>m.bestBid()); assertTrue(sell.limitPrice()>=10025);
                    assertEquals(1,sell.quantity());
                }
                var broke=engine.decide(p,m,neutral,new BotStrategyEngine.Position(0,0,0,0),10000,0,10,1);
                assertEquals(0,broke.quantity());
            }
        }
        assertTrue(buys>50);assertTrue(sells>50);
    }
    @Test void intradayDirectionAloneDoesNotJustifyPayingAnUneconomicSpread() {
        var engine=new BotStrategyEngine(9);var p=profile(Strategy.BOOK_IMBALANCE);
        var m=PriceMetricService.calculate(List.of(),10000,9950,10050,900,100,0);
        assertTrue(engine.executableEdge(p,m,"BUY",10000)<0);
        for(int i=0;i<30;i++) {
            var buy=engine.decide(p,m,neutral,new BotStrategyEngine.Position(0,1000000,0,0),10000,0,10,1);
            assertEquals("BUY",buy.side()); assertFalse(buy.market());
            assertTrue(buy.limitPrice()<m.bestAsk()); assertTrue(buy.quantity()<=2);
        }
        // A real risk exit can still take liquidity; providing quotes must not disable stop losses.
        var sell=engine.decide(p,m,neutral,new BotStrategyEngine.Position(20,1000000,12000,0),10000,0,10,1);
        assertEquals("SELL",sell.side());assertEquals(m.bestBid(),sell.limitPrice());
    }
    @Test void lpBacksCompetitiveExternalQuotesButIgnoresRemoteQuotes() {
        var maker=new MarketMakerEngine(); var m=history(0);
        var risk=new MarketMakerEngine.RiskBook(5000000,400,400,1200,12000000);
        var alone=maker.quotes(m,neutral,risk,10,1,new MarketMakerEngine.QuoteConstraints(8000,12000,0,0));
        double bid=alone.stream().filter(q->q.side().equals("BUY")).mapToDouble(MarketMakerEngine.Quote::price).max().orElseThrow();
        double ask=alone.stream().filter(q->q.side().equals("SELL")).mapToDouble(MarketMakerEngine.Quote::price).min().orElseThrow();
        var backed=maker.quotes(m,neutral,risk,10,1,new MarketMakerEngine.QuoteConstraints(8000,12000,(long)bid,(long)ask));
        assertEquals(8,backed.size());
        assertTrue(backed.stream().filter(q->q.side().equals("BUY")).allMatch(q->q.price()<bid));
        assertTrue(backed.stream().filter(q->q.side().equals("SELL")).allMatch(q->q.price()>ask));
        var remote=maker.quotes(m,neutral,risk,10,1,new MarketMakerEngine.QuoteConstraints(8000,12000,8500,11500));
        assertEquals(alone,remote,"Remote orders must not widen the LP spread");
    }
    @Test void cashBackedFlowDemandWorksWithFlatHistoryAndFallsAsInventoryAccumulates() {
        var engine=new BotStrategyEngine(31); var identical=new BotStrategyEngine(31);
        var p=profile(Strategy.NOISE_RANDOM); var m=history(0);
        assertEquals(0,engine.targetQuantity(p,"ES",0,0,15,10000));
        assertEquals(0,engine.targetQuantity(profile(Strategy.FAST_MOMENTUM),"ES",0,1000000,15,10000));
        double budget=0;
        for(int i=0;i<15;i++) {
            double target=engine.targetQuantity(p,"S"+i,1000000,1000000,15,10000);
            assertEquals(target,identical.targetQuantity(p,"S"+i,1000000,1000000,15,10000));
            budget+=target*10000;
            double empty=engine.score(p,m,neutral,new BotStrategyEngine.Position(0,1000000,0,0,target),10000,0,0);
            double full=engine.score(p,m,neutral,new BotStrategyEngine.Position((int)Math.ceil(target)+2,1000000,0,0,target),10000,0,0);
            assertTrue(empty>p.confidenceThreshold()); assertTrue(full<empty);
        }
        assertTrue(budget<750000);
        assertNotEquals(engine.targetQuantity(p,"ES",0,1000000,15,10000),engine.targetQuantity(p,"ES",60000,1000000,15,10000));
    }
    @Test void swingCanSeeAMultiMinuteTrendAfterShortTermReturnsGoFlat() {
        var trades=List.of(new PriceMetricService.Trade(0,10000,10),new PriceMetricService.Trade(240000,10200,10),
                new PriceMetricService.Trade(300000,10200,10));
        var m=PriceMetricService.calculate(trades,10200,10190,10210,100,100,300000);
        assertEquals(0,m.return20s()); assertTrue(m.return300s()>.019);
        assertTrue(score(Strategy.SWING_TREND,m,0,10200)>.5);
    }
    @Test void seedReproducesProfilesPatternsAndRegimes() {
        assertEquals(BotProfile.defaults(123),BotProfile.defaults(123));
        assertNotEquals(BotProfile.defaults(123),BotProfile.defaults(456));
        PatternEngine a=new PatternEngine(123),b=new PatternEngine(123);
        MarketRegimeEngine ra=new MarketRegimeEngine(123),rb=new MarketRegimeEngine(123);
        for(long time=0;time<1000000;time+=10000) {
            assertEquals(a.environment("A",time),b.environment("A",time));
            ra.advance(time,Map.of("A","RPG","B","RPG")); rb.advance(time,Map.of("A","RPG","B","RPG"));
            assertEquals(ra.environment("A","RPG"),rb.environment("A","RPG"));
        }
    }
    @Test void patternsHaveCooldownAndNaturalTransitions() {
        var engine=new PatternEngine(1);
        assertEquals(50,PatternEngine.Pattern.values().length);
        var from=PatternEngine.Pattern.STRONG_UPTREND;
        var to=PatternEngine.Pattern.BULL_PULLBACK;
        assertTrue(engine.transitionWeight(from,to,List.of())>engine.transitionWeight(from,PatternEngine.Pattern.GAP_DOWN_FADE,List.of()));
        assertTrue(engine.transitionWeight(from,to,List.of(to))<engine.transitionWeight(from,to,List.of()));
        engine.environment("A",0); var first=engine.current("A");
        engine.environment("A",49000); assertEquals(first,engine.current("A"));
    }
    @Test void regimePersistsAndSectorShockIsShared() {
        var engine=new MarketRegimeEngine(1);
        engine.advance(0,Map.of("A","RPG","B","RPG")); var first=engine.regime();
        engine.advance(119000,Map.of("A","RPG","B","RPG")); assertEquals(first,engine.regime());
        // Unknown symbols have no individual shock, exposing exactly the shared sector factor.
        assertEquals(engine.environment("X","RPG"),engine.environment("Y","RPG"));
    }
    @Test void lpSkewsInventoryAndWidensSpreadWithVolatility() {
        var engine=new MarketMakerEngine(); var m=history(0);
        var normal=new MarketMakerEngine.RiskBook(5000000,400,400,1200,12000000);
        var overstocked=new MarketMakerEngine.RiskBook(5000000,700,400,1200,12000000);
        assertTrue(engine.reservationPrice(m,overstocked,10)<engine.reservationPrice(m,normal,10));
        var volatileEnv=new MarketEnvironment(0,0,3,1,1,1,.5,0,0,0,1,2);
        assertTrue(engine.halfSpread(m,volatileEnv,normal,10)>engine.halfSpread(m,neutral,normal,10));
        var constraints=new MarketMakerEngine.QuoteConstraints(8000,12000,0,0);
        var quotes=engine.quotes(m,neutral,normal,10,1,constraints);
        assertEquals(4,quotes.stream().filter(q->q.side().equals("BUY")).count());
        assertEquals(4,quotes.stream().filter(q->q.side().equals("SELL")).count());
        assertTrue(engine.quotes(m,neutral,new MarketMakerEngine.RiskBook(0,0,400,1200,12000000),10,1,constraints).isEmpty());
    }
    @Test void thinBooksRefillWithEightSharesPerEmergencyLevel() {
        var engine=new MarketMakerEngine();
        var empty=PriceMetricService.calculate(List.of(),10000,10000,10000,0,0,0);
        var risk=new MarketMakerEngine.RiskBook(5000000,100,100,1200,12000000);
        var quotes=engine.quotes(empty,neutral,risk,10,1,
                new MarketMakerEngine.QuoteConstraints(8000,12000,0,0));
        assertEquals(8,quotes.size());
        assertTrue(quotes.stream().allMatch(q->q.quantity()==8));
    }
    @Test void oldJumpsAndTradeCountTruncationDoNotInflateCurrentVolatility() {
        var sparse=List.of(new PriceMetricService.Trade(0,8000,10),
                new PriceMetricService.Trade(10000,10000,10),new PriceMetricService.Trade(590000,10000,10));
        var metrics=PriceMetricService.calculate(sparse,10000,9990,10010,20,20,600000);
        assertEquals(0,metrics.volatility()); assertEquals(50,metrics.rsi());
        List<PriceMetricService.Trade> dense=new ArrayList<>();
        for(int i=0;i<180;i++) dense.add(new PriceMetricService.Trade(i*1000L,i<100?10000:11000,1));
        assertEquals(0,PriceMetricService.calculate(dense,11000,10990,11010,20,20,179000).volatility());
    }
    @Test void lpKeepsDistinctPassiveLevelsAtBothBandEdgesWithoutCreatingResources() {
        var engine=new MarketMakerEngine();
        var volatileEnv=new MarketEnvironment(0,0,5,1,1,2,.3,0,0,0,2,5);
        for(long last:List.of(8000L,8010L,11990L,12000L,19990L,20000L)) {
            long lower=last<15000?8000:16000,upper=last<15000?12000:24000;
            List<PriceMetricService.Trade> trades=new ArrayList<>();
            for(int i=0;i<60;i++) trades.add(new PriceMetricService.Trade(i*1000L,last*(i%2==0?.99:1),10));
            var metrics=PriceMetricService.calculate(trades,last,last-10,last+10,20,20,59000);
            var risk=new MarketMakerEngine.RiskBook(3333333,400,400,1200,24000000);
            var quotes=engine.quotes(metrics,volatileEnv,risk,PriceLimitPolicy.tickSize(last),1,
                    new MarketMakerEngine.QuoteConstraints(lower,upper,0,0));
            assertEquals(8,quotes.size(),"last="+last);
            assertEquals(8,quotes.stream().map(q->q.side()+q.price()).distinct().count());
            for(var q:quotes) {
                assertTrue(q.price()>=lower && q.price()<=upper);
                assertEquals(0,(long)q.price()%PriceLimitPolicy.tickSize((long)q.price()));
            }
            double bid=quotes.stream().filter(q->q.side().equals("BUY")).mapToDouble(MarketMakerEngine.Quote::price).max().orElseThrow();
            double ask=quotes.stream().filter(q->q.side().equals("SELL")).mapToDouble(MarketMakerEngine.Quote::price).min().orElseThrow();
            assertTrue(bid<ask);
            assertTrue((ask-bid)/last<.012,"spread must not approach a VI-sized gap");
            assertTrue(quotes.stream().filter(q->q.side().equals("BUY")).mapToDouble(q->Math.ceil(q.price()*q.quantity()*1.001)).sum()<=risk.cashBudget());
            assertTrue(quotes.stream().filter(q->q.side().equals("SELL")).mapToInt(MarketMakerEngine.Quote::quantity).sum()<=risk.inventory());
        }
    }
    @Test void lpNeverCrossesExternalQuotesEvenWhenOneSideCannotFitLegally() {
        var engine=new MarketMakerEngine(); var metrics=history(0);
        var risk=new MarketMakerEngine.RiskBook(5000000,400,400,1200,12000000);
        var quotes=engine.quotes(metrics,neutral,risk,10,1,new MarketMakerEngine.QuoteConstraints(8000,12000,12000,12010));
        assertTrue(quotes.stream().noneMatch(q->q.side().equals("SELL")));
        assertTrue(quotes.stream().allMatch(q->q.price()<12010 && q.price()<=12000));
    }
    @Test void ownBookCannotWalkReservationPriceAwayFromUnchangedTrades() {
        var engine=new MarketMakerEngine(); var risk=new MarketMakerEngine.RiskBook(5000000,400,400,1200,12000000);
        var metrics=PriceMetricService.calculate(List.of(new PriceMetricService.Trade(0,10000,20)),10000,11990,12010,20,20,1000);
        assertTrue(Math.abs(engine.reservationPrice(metrics,risk,10)-10000)<25);
    }
    @Test void lpAnchorUsesFundamentalAfterAOneSidedLowPrint() {
        var engine=new MarketMakerEngine();
        var trades=List.of(new PriceMetricService.Trade(0,10000,100),new PriceMetricService.Trade(59000,6500,10));
        var metrics=PriceMetricService.calculate(trades,6500,6490,6510,10,10,60000);
        var risk=new MarketMakerEngine.RiskBook(5000000,100,100,1200,12000000);
        assertTrue(engine.fairValue(metrics,10000)>metrics.lastPrice());
        assertTrue(engine.fairValue(metrics,10000)<=metrics.lastPrice()*1.005);
        assertTrue(engine.reservationPrice(metrics,risk,10,10000)>metrics.lastPrice());
    }
    @Test void lpUsesLastExecutionWhenBookHasDepthButNoRecentTrades() {
        var engine=new MarketMakerEngine();
        var metrics=PriceMetricService.calculate(List.of(),7710,8360,8420,171,49,60000);
        var risk=new MarketMakerEngine.RiskBook(5000000,400,400,1200,12000000);
        var quotes=engine.quotes(metrics,neutral,risk,10,1,
                new MarketMakerEngine.QuoteConstraints(6000,10000,0,0),8400);
        assertEquals(7710,engine.fairValue(metrics,8400));
        var stabilized=BotActivityEngine.stabilizeQuietBook(metrics);
        assertEquals(7710,stabilized.midPrice());
        assertEquals(7710,stabilized.vwap());
        assertTrue(quotes.stream().allMatch(q->Math.abs(q.price()-7710)<=100),
                () -> "quotes detached from last execution: "+quotes);
    }
    @Test void quietBookPreservesNearbyExecutableQuotesInsteadOfInventingAnInsideSpread() {
        var quiet=PriceMetricService.calculate(List.of(),10000,9980,10020,50,50,1000);
        var stabilized=BotActivityEngine.stabilizeQuietBook(quiet);
        assertEquals(9980,stabilized.bestBid());assertEquals(10020,stabilized.bestAsk());
        assertEquals(10000,stabilized.markPrice());
        var atDailyFloor=BotActivityEngine.stabilizeQuietBook(PriceMetricService.calculate(List.of(),2000,2015,2025,30,30,1000));
        assertEquals(2025,atDailyFloor.bestAsk(),"buyers must be able to lift the real offer after a limit-down period");
        assertEquals(2000,atDailyFloor.markPrice());
    }

    @Test void metricsResistTinyLastPrintAndHaveNeutralWarmup() {
        var trades=List.of(new PriceMetricService.Trade(0,10000,1000),new PriceMetricService.Trade(1000,11000,1));
        var m=PriceMetricService.calculate(trades,10000,9990,10010,100,100,1000);
        assertEquals(11000,m.lastPrice()); assertTrue(m.markPrice()<10020);
        var empty=PriceMetricService.calculate(List.of(),10000,9000,12000,0,0,0);
        assertEquals(10000,empty.markPrice()); assertEquals(0,empty.return5s()); assertEquals(50,empty.rsi());
    }
    @Test void profilesLimitResourcesAndDiversifyOrders() {
        var engine=new BotStrategyEngine(42); var m=history(10); Set<Double> prices=new HashSet<>();
        for(BotProfile p:BotProfile.defaults(42)) {
            for(int i=0;i<20;i++) {
                var d=engine.decide(p,m,neutral,new BotStrategyEngine.Position(0,30000,0,0),12000,.04,10,1);
                assertTrue(d.quantity()<=p.maxOrderSize());
                if(d.side().equals("SELL")) assertEquals(0,d.quantity());
                if(d.side().equals("BUY")) assertTrue(d.quantity()<=3);
                prices.add(d.limitPrice()); assertTrue(d.ttlMillis()>0);
            }
        }
        assertTrue(prices.size()>3);
    }
}
