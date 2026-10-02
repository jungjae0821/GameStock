package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Bounded admission and independent DB workers. Success is acknowledged only after commit.
 * The receipt shares the balance/order transaction, so an ambiguous commit can be retried
 * without executing its orders twice. Uncommitted work is never advertised as a fill.
 */
@Component
@org.springframework.context.annotation.DependsOn({"matchingEngine","marketOwnership","connectionManager"})
public class PersistenceWorker {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MarketMetrics metrics;
    private final TransactionTemplate transaction;
    private final ThreadPoolExecutor workers;
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final Set<CompletableFuture<?>> outstanding=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong lastPruned=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    @Value("${market.persistence.max-attempts:4}") private int attempts=4;
    public PersistenceWorker(JdbcTemplate jdbc,ObjectMapper json,MarketMetrics metrics){
        this.jdbc=jdbc;this.json=json;this.metrics=metrics;
        transaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
        workers=new ThreadPoolExecutor(5,5,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{
            Thread t=new Thread(r,"market-persistence");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    @PostConstruct public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS market_batch_receipts (id VARCHAR(64) PRIMARY KEY,payload MEDIUMTEXT NOT NULL,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX(created_at))");
    }
    public <T> CompletableFuture<T> submit(String id,Class<T> type,int count,Supplier<T> work){
        var result=new CompletableFuture<T>();
        if(stopping.get()){result.completeExceptionally(unavailable());return result;}
        outstanding.add(result);result.whenComplete((value,error)->outstanding.remove(result));
        try{workers.execute(()->{
            metrics.gauge("persistence.pending",workers.getQueue().size());
            try{result.complete(write(id,type,count,work));}
            catch(Throwable error){metrics.add("persistence.failures",1);result.completeExceptionally(error);}
        });metrics.gauge("persistence.pending",workers.getQueue().size());}
        catch(RejectedExecutionException full){metrics.add("persistence.rejected",1);result.completeExceptionally(unavailable());}
        return result;
    }
    private <T> T write(String id,Class<T> type,int count,Supplier<T> work){
        for(int attempt=1;;attempt++){
            long started=System.nanoTime();
            try{
                T result=transaction.execute(status->{
                    // Insert serializes duplicate executions of the same batch, even on another connection.
                    int inserted=jdbc.update("INSERT IGNORE INTO market_batch_receipts(id,payload) VALUES (?,'')",id);
                    if(inserted==0)return decode(jdbc.queryForObject("SELECT payload FROM market_batch_receipts WHERE id=?",String.class,id),type);
                    T value=work.get();
                    jdbc.update("UPDATE market_batch_receipts SET payload=? WHERE id=?",encode(value),id);
                    return value;
                });
                metrics.add("db.batches",1);metrics.add("db.batch.items",count);prune();return result;
            }catch(TransientDataAccessException|RecoverableDataAccessException|DataAccessResourceFailureException|TransactionException error){
                if(attempt>=Math.max(1,Math.min(8,attempts)))throw error;
                metrics.add("persistence.retries",1);
                LoggerFactory.getLogger(getClass()).warn("Retrying market batch {} attempt {}/{} ({})",id,attempt+1,attempts,error.getClass().getSimpleName());
                try{Thread.sleep(Math.min(1000,50L<<(attempt-1)));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
            }finally{metrics.timing("db.write",System.nanoTime()-started);}
        }
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("Cannot serialize batch receipt",error);}}
    private void prune(){
        long previous=lastPruned.get(),now=System.currentTimeMillis();
        if(now-previous<60000||!lastPruned.compareAndSet(previous,now))return;
        try{jdbc.update("DELETE FROM market_batch_receipts WHERE created_at<CURRENT_TIMESTAMP-INTERVAL 1 HOUR LIMIT 10000");}
        catch(DataAccessException error){LoggerFactory.getLogger(getClass()).warn("Batch receipt retention will retry next minute");}
    }
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception error){throw new IllegalStateException("Cannot read committed batch receipt",error);}}
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"주문 처리 중입니다. 잠시 후 주문 내역을 확인해 주세요.");}
    public int pending(){return workers.getQueue().size()+workers.getActiveCount();}
    @PreDestroy public void close(){stopping.set(true);MatchingEngine.executorShutdown(workers);outstanding.forEach(f->f.completeExceptionally(unavailable()));metrics.gauge("persistence.pending",pending());}
}
