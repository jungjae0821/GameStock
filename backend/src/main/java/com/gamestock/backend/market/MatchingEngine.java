package com.gamestock.backend.market;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Pure in-memory matching. JDBC remains on the persistence worker, never these threads.
 * A transaction owns its book until commit/rollback; it cannot be borrowed by another task.
 */
@Component
public class MatchingEngine {
    private final ExecutorService executor;
    private final MarketMetrics metrics;
    @org.springframework.beans.factory.annotation.Autowired
    public MatchingEngine(MarketMetrics metrics){
        this.metrics=metrics;
        executor=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{
            Thread t=new Thread(r,"matching-engine");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    private MatchingEngine(){executor=null;metrics=new MarketMetrics();}
    static MatchingEngine direct(){return new MatchingEngine();}
    <T> T compute(Supplier<T> task){
        if(executor==null)return measured(task);
        try{return CompletableFuture.supplyAsync(()->measured(task),executor).join();}
        catch(CompletionException error){if(error.getCause() instanceof RuntimeException cause)throw cause;throw error;}
    }
    private <T> T measured(Supplier<T> task){long start=System.nanoTime();try{return task.get();}finally{metrics.timing("matching",System.nanoTime()-start);}}
    @PreDestroy public void close(){executorShutdown(executor);}
    static void executorShutdown(ExecutorService executor){
        if(executor==null)return;executor.shutdown();
        try{if(!executor.awaitTermination(30,TimeUnit.SECONDS))executor.shutdownNow();}
        catch(InterruptedException interrupted){executor.shutdownNow();Thread.currentThread().interrupt();}
    }
}
