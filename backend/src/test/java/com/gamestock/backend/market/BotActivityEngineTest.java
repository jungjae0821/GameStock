package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.BatchOrderBook.*;

class BotActivityEngineTest {
    @Test void scaledProfilesAreReproducibleAndAccountsStayUnique() {
        var a=BotProfile.activePopulation(91,6020);var b=BotProfile.activePopulation(91,6020);
        assertEquals(a,b);assertEquals(BotProfile.defaults(91),a.subList(0,20));
        assertEquals(6020,a.stream().map(BotProfile::username).distinct().count());
        assertEquals(6020,a.stream().map(BotProfile::nickname).distinct().count());
        assertEquals(2,a.get(20).positionLimit());assertNotEquals(a.get(20).aggression(),a.get(28).aggression());
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
