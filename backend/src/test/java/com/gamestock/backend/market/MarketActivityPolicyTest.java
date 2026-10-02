package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MarketActivityPolicyTest {
    @Test void idleStartupAndRequestGraceDoNotRequireSyntheticMarketEvents() {
        var policy=new MarketActivityPolicy();assertFalse(policy.active(1000,120000));
        policy.touch(2000);assertTrue(policy.active(121999,120000));assertFalse(policy.active(122000,120000));
    }
    @Test void ConnectedViewerKeepsMarketActiveAndLastDisconnectStartsGracePeriod() {
        var policy=new MarketActivityPolicy();policy.connected("a",1000);policy.connected("b",1000);
        policy.disconnected("a",2000);assertTrue(policy.active(500000,120000));
        policy.disconnected("b",600000);assertTrue(policy.active(719999,120000));assertFalse(policy.active(720000,120000));
        policy.disconnected("b",800000);assertFalse(policy.active(800000,120000));
    }
    @Test void serviceSwitchesRateAndCadenceTogetherOnArrivalDepartureAndNewRequest() {
        var market=new MarketService(e->{},mock(org.springframework.jdbc.core.JdbcTemplate.class),mock(UserFeatureService.class),mock(TradingProtectionService.class));
        ReflectionTestUtils.setField(market,"adaptiveActivity",true);
        ReflectionTestUtils.setField(market,"idleAfterMillis",1000L);
        at(market,1000);
        assertEquals(30,market.currentTargetPerSymbol());assertEquals(500,market.participantCadenceMillis());
        market.marketViewerConnected("viewer");
        assertEquals(300,market.currentTargetPerSymbol());assertEquals(100,market.participantCadenceMillis());
        at(market,100000);assertTrue(market.activeSimulation());
        market.marketViewerDisconnected("viewer");
        at(market,100999);assertEquals(300,market.currentTargetPerSymbol());
        at(market,101000);assertEquals(30,market.currentTargetPerSymbol());assertEquals(500,market.participantCadenceMillis());
        market.recordHumanActivity();
        assertEquals(300,market.currentTargetPerSymbol());assertEquals(100,market.participantCadenceMillis());
        ReflectionTestUtils.setField(market,"adaptiveActivity",false);
        at(market,1000000);assertTrue(market.activeSimulation());
    }
    @Test void idleDoesNoWorkUntilHalfHourThenAllowsOnlyFiveBatchesPerWorker() {
        var policy=new MarketActivityPolicy();var maintenance=new AtomicInteger();
        policy.maintain(0,false,1800000,5,maintenance::incrementAndGet);
        for(long now=500;now<1800000;now+=500) {
            policy.maintain(now,false,1800000,5,maintenance::incrementAndGet);
            assertFalse(policy.workDue("batch:0",now,false));
        }
        assertEquals(1,maintenance.get());
        policy.maintain(1800000,false,1800000,5,maintenance::incrementAndGet);
        for(int shard=0;shard<4;shard++) {
            for(int batch=0;batch<5;batch++)assertTrue(policy.claim("batch:"+shard,1800000+batch*200,false)>=0);
            assertEquals(-1,policy.claim("batch:"+shard,1801000,false));
        }
        assertEquals(2,maintenance.get());
        policy.maintain(1806000,false,1800000,5,maintenance::incrementAndGet);
        assertFalse(policy.workDue("unused-worker",1806000,false),"unused quota must also expire");
        assertEquals(2,maintenance.get());
    }
    @Test void longSuspensionNeverReplaysMissedWindowsAndArrivalAbandonsIdleQuota() {
        var policy=new MarketActivityPolicy();var maintenance=new AtomicInteger();
        policy.maintain(0,false,1800000,5,maintenance::incrementAndGet);
        long late=30L*86400000;
        policy.maintain(late,false,1800000,5,maintenance::incrementAndGet);
        long pulse=policy.claim("batch:0",late,false);
        policy.touch(late+1);
        assertEquals(-1,policy.claim("batch:0",late+1,true),"new orders wait for wake cleanup");
        policy.maintain(late+1,true,1800000,5,maintenance::incrementAndGet);
        assertTrue(policy.claim("batch:0",late+1,true)>pulse);
        for(int i=0;i<20;i++)assertTrue(policy.claim("batch:0",late+2,true)>=0);
        assertEquals(3,maintenance.get());
        assertFalse(policy.pulseOpen(late+2));
    }
    @Test void failedMaintenanceCannotOpenTheWakeBarrier() {
        var policy=new MarketActivityPolicy();
        assertThrows(IllegalStateException.class,()->policy.maintain(0,true,1800000,5,()->{throw new IllegalStateException("rollback");}));
        assertEquals(-1,policy.claim("batch:0",0,true));
        policy.maintain(1,true,1800000,5,()->{});
        assertTrue(policy.claim("batch:0",1,true)>=0);
    }
    @Test void visitorAfterProcessSuspensionRequiresOverdueCleanupEvenIfIdleWasNotObserved() {
        var policy=new MarketActivityPolicy();
        policy.maintain(0,true,1800000,5,()->{});
        assertFalse(policy.maintenanceDue(1000,true));
        assertTrue(policy.maintenanceDue(1800000,true));
    }
    @Test void simultaneousVisitorsRunWakeMaintenanceOnlyOnce() throws Exception {
        var policy=new MarketActivityPolicy();var count=new AtomicInteger();var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(8);
        try {
            var jobs=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<20;i++)jobs.add(pool.submit(()->{
                try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}
                policy.maintain(1000,true,1800000,5,count::incrementAndGet);
            }));
            start.countDown();for(var job:jobs)job.get(5,TimeUnit.SECONDS);
            assertEquals(1,count.get());
        } finally {pool.shutdownNow();}
    }
    private static void at(MarketService market,long millis) {
        ReflectionTestUtils.setField(market,"clock",Clock.fixed(Instant.ofEpochMilli(millis),ZoneOffset.UTC));
    }
}
