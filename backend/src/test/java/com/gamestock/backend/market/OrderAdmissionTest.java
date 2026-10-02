package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderAdmissionTest {
    @Test void databaseStallCannotGrowHumanAdmissionWithoutBound()throws Exception{
        var market=mock(MarketService.class);var writer=mock(PersistenceWorker.class);var metrics=new MarketMetrics();
        var entered=new CountDownLatch(1);var first=new AtomicBoolean(true);var blocked=new CompletableFuture<OrderBatch.Outcome[]>();
        doAnswer(call->{if(first.getAndSet(false)){entered.countDown();return blocked;}return CompletableFuture.failedFuture(PersistenceWorker.unavailable());})
            .when(writer).submit(anyString(),eq(OrderBatch.Outcome[].class),anyInt(),any());
        var orders=new OrderService(market,writer,metrics,event->{});
        try{
            orders.submit(1,new MarketModels.OrderRequest("A","BUY",1,"LIMIT",10000L));assertTrue(entered.await(2,TimeUnit.SECONDS));
            int rejected=0;
            for(int i=0;i<2050;i++)if(orders.submit(1,new MarketModels.OrderRequest("A","BUY",1,"LIMIT",10000L)).isCompletedExceptionally())rejected++;
            assertEquals(2,rejected);assertEquals(2048,metrics.snapshot().get("human.pending"));
        }finally{blocked.completeExceptionally(PersistenceWorker.unavailable());orders.close();}
    }
}
