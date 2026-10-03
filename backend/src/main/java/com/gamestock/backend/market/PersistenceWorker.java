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
import java.util.concurrent.atomic.AtomicLong;
import java.sql.SQLException;
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
    private static final long RECEIPT_RETENTION_NANOS=TimeUnit.HOURS.toNanos(1);
    private static final long STORAGE_BACKOFF_NANOS=TimeUnit.SECONDS.toNanos(30);
    private final AtomicLong cleanupUntil=new AtomicLong(System.nanoTime()+RECEIPT_RETENTION_NANOS);
    private final AtomicLong storageRetryAt=new AtomicLong();
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
        if(id.startsWith("bot:"))throw new IllegalArgumentException("Reserved receipt namespace");
        return enqueue(id,null,type,count,work);
    }
    /** OrderService admits only one unfinished batch per shard. Its last receipt is reusable
     * only after that batch has finished all commit/retry handling; no client can replay it.
     * A lost commit response still finds the same batch token before the next batch is admitted.
     */
    public <T> CompletableFuture<T> submitBot(int shard,String batchId,Class<T> type,int count,Supplier<T> work){
        if(shard<0)throw new IllegalArgumentException("Invalid bot shard");
        return enqueue(batchId,"bot:"+shard,type,count,work);
    }
    public boolean acceptingWrites(){return !stopping.get()&&!storagePaused();}
    private boolean storagePaused(){long until=storageRetryAt.get();return until!=0&&System.nanoTime()<until;}
    private <T> CompletableFuture<T> enqueue(String id,String slot,Class<T> type,int count,Supplier<T> work){
        var result=new CompletableFuture<T>();
        if(!acceptingWrites()){result.completeExceptionally(unavailable());return result;}
        outstanding.add(result);result.whenComplete((value,error)->outstanding.remove(result));
        try{workers.execute(()->{
            metrics.gauge("persistence.pending",workers.getQueue().size());
            try{result.complete(write(id,slot,type,count,work));}
            catch(Throwable error){metrics.add("persistence.failures",1);result.completeExceptionally(error);}
        });metrics.gauge("persistence.pending",workers.getQueue().size());}
        catch(RejectedExecutionException full){metrics.add("persistence.rejected",1);result.completeExceptionally(unavailable());}
        return result;
    }
    private <T> T write(String id,String slot,Class<T> type,int count,Supplier<T> work){
        for(int attempt=1;;attempt++){
            if(storagePaused())throw unavailable();
            long started=System.nanoTime();
            try{
                T result=transaction.execute(status->{
                    if(slot!=null){
                        // The upsert obtains an exclusive row lock even when the slot exists.
                        // Four matching groups therefore retain four rows, not 144,000 per hour.
                        jdbc.update("INSERT INTO market_batch_receipts(id,payload) VALUES (?,'') ON DUPLICATE KEY UPDATE id=VALUES(id)",slot);
                        String stored=jdbc.queryForObject("SELECT payload FROM market_batch_receipts WHERE id=?",String.class,slot);
                        if(stored!=null&&!stored.isBlank()){
                            try{
                                var receipt=json.readTree(stored);
                                if(id.equals(receipt.path("batchId").asText()))return json.treeToValue(receipt.get("result"),type);
                            }catch(Exception error){throw new IllegalStateException("Cannot read committed bot receipt",error);}
                        }
                        T value=work.get();
                        var receipt=json.createObjectNode();receipt.put("batchId",id);receipt.set("result",json.valueToTree(value));
                        jdbc.update("UPDATE market_batch_receipts SET payload=?,created_at=CURRENT_TIMESTAMP WHERE id=?",encode(receipt),slot);
                        return value;
                    }
                    // Insert serializes duplicate executions of the same batch, even on another connection.
                    int inserted=jdbc.update("INSERT IGNORE INTO market_batch_receipts(id,payload) VALUES (?,'')",id);
                    if(inserted==0)return decode(jdbc.queryForObject("SELECT payload FROM market_batch_receipts WHERE id=?",String.class,id),type);
                    T value=work.get();
                    jdbc.update("UPDATE market_batch_receipts SET payload=? WHERE id=?",encode(value),id);
                    return value;
                });
                cleanupUntil.set(System.nanoTime()+RECEIPT_RETENTION_NANOS);
                metrics.gauge("persistence.storageBlocked",0);
                metrics.add("db.batches",1);metrics.add("db.batch.items",count);return result;
            }catch(RuntimeException error){
                if(storageFull(error)){
                    long now=System.nanoTime(),until=now+STORAGE_BACKOFF_NANOS;
                    long before=storageRetryAt.getAndAccumulate(until,Math::max);
                    metrics.add("persistence.storageFailures",1);metrics.gauge("persistence.storageBlocked",1);
                    if(before<=now)LoggerFactory.getLogger(getClass()).error("Market database storage is full; pausing new writes for 30 seconds. Check database quota/disk space. Receipt cleanup remains active.",error);
                    throw unavailable();
                }
                boolean retryable=error instanceof TransientDataAccessException||error instanceof RecoverableDataAccessException
                        ||error instanceof DataAccessResourceFailureException||error instanceof TransactionException;
                if(!retryable||attempt>=Math.max(1,Math.min(8,attempts)))throw error;
                metrics.add("persistence.retries",1);
                LoggerFactory.getLogger(getClass()).warn("Retrying market batch {} attempt {}/{} ({})",id,attempt+1,attempts,error.getClass().getSimpleName());
                try{Thread.sleep(Math.min(1000,50L<<(attempt-1)));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
            }finally{metrics.timing("db.write",System.nanoTime()-started);}
        }
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("Cannot serialize batch receipt",error);}}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000,initialDelay=30000)
    public void prune(){
        if(stopping.get()||cleanupUntil.get()==0)return;
        try{
            int deleted=jdbc.update("DELETE FROM market_batch_receipts WHERE created_at<CURRENT_TIMESTAMP-INTERVAL 1 HOUR ORDER BY created_at,id LIMIT 2000");
            metrics.add("persistence.receiptsPruned",deleted);
            long until=cleanupUntil.get();
            if(deleted==0&&System.nanoTime()>until)cleanupUntil.compareAndSet(until,0);
        }catch(DataAccessException error){LoggerFactory.getLogger(getClass()).warn("Batch receipt cleanup failed; will retry independently of order writes ({})",error.getClass().getSimpleName());}
    }
    static boolean storageFull(Throwable error){
        for(int depth=0;error!=null&&depth<20;depth++,error=error.getCause())
            if(error instanceof SQLException sql){
                for(int next=0;sql!=null&&next<20;next++,sql=sql.getNextException())
                    if(sql.getErrorCode()==1114||sql.getErrorCode()==1021)return true;
            }
        return false;
    }
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception error){throw new IllegalStateException("Cannot read committed batch receipt",error);}}
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"주문 처리 중입니다. 잠시 후 주문 내역을 확인해 주세요.");}
    public int pending(){return workers.getQueue().size()+workers.getActiveCount();}
    @PreDestroy public void close(){stopping.set(true);MatchingEngine.executorShutdown(workers);outstanding.forEach(f->f.completeExceptionally(unavailable()));metrics.gauge("persistence.pending",pending());}
}
