package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotTradingSchedulerTest {
    @Test void initialMaintenanceFailureDoesNotPreventBotTasks() {
        var market=mock(MarketService.class);var timers=mock(TaskScheduler.class);
        when(market.matchingEnabled()).thenReturn(true);
        when(market.botStockCodes()).thenReturn(List.of("A"));
        when(market.batchBotsEnabled()).thenReturn(true);when(market.inlineLiquidity()).thenReturn(true);
        when(market.batchShardCount()).thenReturn(4);when(market.participantBotUsernames()).thenReturn(List.of());
        doThrow(new IllegalStateException("transient database error")).when(market).maintainScheduledMarket();
        doAnswer(call->mock(ScheduledFuture.class)).when(timers).schedule(any(Runnable.class),any(Trigger.class));
        var scheduler=new BotTradingScheduler(market,timers);
        try { scheduler.start(); verify(timers,times(4)).schedule(any(Runnable.class),any(Trigger.class)); }
        finally { scheduler.stop(); }
    }

    @Test void idleWakeChecksDoNotInvokeTransactionalMatchingOrSeedLpOrders() {
        var market=mock(MarketService.class);var timers=mock(TaskScheduler.class);
        when(market.matchingEnabled()).thenReturn(true);
        when(market.ownerAvailable()).thenReturn(true);
        when(market.botStockCodes()).thenReturn(List.of("A","B"));
        when(market.batchBotsEnabled()).thenReturn(true);when(market.inlineLiquidity()).thenReturn(true);
        when(market.batchShardCount()).thenReturn(4);when(market.participantBotUsernames()).thenReturn(List.of());
        List<Runnable> jobs=new ArrayList<>();
        doAnswer(call->{jobs.add(call.getArgument(0));return mock(ScheduledFuture.class);})
                .when(timers).schedule(any(Runnable.class),any(Trigger.class));
        var scheduler=new BotTradingScheduler(market,timers);
        try {
            scheduler.start();assertEquals(4,jobs.size());verify(market,never()).liquidityBotAction(anyString(),anyString());
            jobs.forEach(Runnable::run);verify(market,never()).participantBatch(anyInt());
            when(market.botWorkDue("batch:2")).thenReturn(true);
            jobs.forEach(Runnable::run);verify(market).participantBatch(2);
            verify(market,times(1)).participantBatch(anyInt());
        } finally {scheduler.stop();}
    }
}
