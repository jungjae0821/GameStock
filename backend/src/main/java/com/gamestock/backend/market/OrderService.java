package com.gamestock.backend.market;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.gamestock.backend.market.MarketModels.*;

/** Transport-independent admission. Human FIFO is drained ahead of future bot ticks;
 * at most 200 independent human orders share a transaction, with a 2 ms collection window.
 */
@Service
public class OrderService {
    private record Pending(OrderBatch.Command command,CompletableFuture<OrderResult> result,Long cancelId,CompletableFuture<Void> cancelled){
        Pending(OrderBatch.Command command,CompletableFuture<OrderResult> result){this(command,result,null,null);}
        CompletableFuture<?> future(){return cancelId==null?result:cancelled;}
    }
    private final MarketService market;
    private final PersistenceWorker persistence;
    private final MarketMetrics metrics;
    private final ApplicationEventPublisher events;
    private final ArrayBlockingQueue<Pending> humans=new ArrayBlockingQueue<>(2048);
    private final Set<Integer> botPending=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running=new AtomicBoolean(true);
    private final ExecutorService admission=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"human-order-batches");t.setDaemon(true);return t;});
    public OrderService(MarketService market,PersistenceWorker persistence,MarketMetrics metrics,ApplicationEventPublisher events){
        this.market=market;this.persistence=persistence;this.metrics=metrics;this.events=events;
        admission.execute(this::drain);
    }
    public CompletableFuture<OrderResult> submit(long userId,OrderRequest request){
        var result=new CompletableFuture<OrderResult>();
        try{market.requireMatchingOwner();validate(request);}
        catch(RuntimeException error){reject(userId,result,error);return result;}
        metrics.add("human.orders",1);
        if(!running.get()||!humans.offer(new Pending(new OrderBatch.Command(userId,request),result)))reject(userId,result,PersistenceWorker.unavailable());
        metrics.gauge("human.pending",humans.size());return result;
    }
    public CompletableFuture<Void> cancel(long userId,long orderId){
        var result=new CompletableFuture<Void>();
        try{market.requireMatchingOwner();if(orderId<=0)throw new IllegalArgumentException("주문 번호를 확인해 주세요.");}
        catch(RuntimeException error){reject(userId,result,error);return result;}
        if(!running.get()||!humans.offer(new Pending(new OrderBatch.Command(userId,null),null,orderId,result)))
            reject(userId,result,PersistenceWorker.unavailable());
        return result;
    }
    public CompletableFuture<List<BotActivityEngine.Activity>> submitBotBatch(int shard){
        if(!running.get()||!botPending.add(shard))return CompletableFuture.completedFuture(List.of());
        var retryPlan=new java.util.concurrent.atomic.AtomicReference<BotBatchPlan>();
        return persistence.submit(UUID.randomUUID().toString(),BotActivityEngine.Activity[].class,1,()->
            market.participantBatch(shard,retryPlan).toArray(BotActivityEngine.Activity[]::new)
        ).handle((activity,error)->{botPending.remove(shard);if(error!=null)throw new CompletionException(error);return List.of(activity);});
    }
    private void drain(){
        Pending carry=null;
        while(running.get()||!humans.isEmpty()||carry!=null)try{
            Pending first=carry==null?humans.poll(100,TimeUnit.MILLISECONDS):carry;carry=null;if(first==null)continue;
            if(first.cancelId!=null){
                try{
                    persistence.submit(UUID.randomUUID().toString(),Boolean.class,1,()->{market.requireMatchingOwner();market.cancelOrder(first.cancelId,first.command.userId());return true;}).join();
                    first.cancelled.complete(null);
                }catch(RuntimeException error){reject(first.command.userId(),first.cancelled,error instanceof CompletionException?error.getCause():error);}
                continue;
            }
            List<Pending> batch=new ArrayList<>();batch.add(first);
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(2);
            while(batch.size()<200){long remaining=end-System.nanoTime();if(remaining<=0)break;Pending next=humans.poll(remaining,TimeUnit.NANOSECONDS);if(next==null)break;if(next.cancelId!=null){carry=next;break;}batch.add(next);}
            try{
                var outcomes=persistence.submit(UUID.randomUUID().toString(),OrderBatch.Outcome[].class,batch.size(),()->
                    market.submitOrders(batch.stream().map(Pending::command).toList()).toArray(OrderBatch.Outcome[]::new)).join();
                for(int i=0;i<batch.size();i++){
                    Pending item=batch.get(i);var outcome=outcomes[i];
                    if(outcome.error()!=null)reject(item.command.userId(),item.result,new IllegalArgumentException(outcome.error()));
                    else item.result.complete(outcome.result());
                }
            }catch(RuntimeException error){Throwable cause=error instanceof CompletionException?error.getCause():error;for(Pending item:batch)reject(item.command.userId(),item.result,cause);}
            metrics.gauge("human.pending",humans.size());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
        if(carry!=null)carry.future().completeExceptionally(PersistenceWorker.unavailable());
        Pending pending;while((pending=humans.poll())!=null)pending.future().completeExceptionally(PersistenceWorker.unavailable());
    }
    private void reject(long user,CompletableFuture<?> result,Throwable error){
        metrics.add("human.rejected",1);
        result.completeExceptionally(error instanceof IllegalArgumentException||error instanceof org.springframework.web.server.ResponseStatusException?error:PersistenceWorker.unavailable());
        events.publishEvent(new UserMarketEvent(user,"ORDER_REJECTED",Map.of("message",error instanceof IllegalArgumentException?error.getMessage():"주문 내역을 확인해 주세요.")));
    }
    static void validate(OrderRequest request){
        if(request==null||request.stockCode()==null||request.stockCode().isBlank()||request.stockCode().length()>32||request.quantity()<1||request.quantity()>1000)
            throw new IllegalArgumentException("종목과 주문 수량을 확인해 주세요.");
        if(!"BUY".equalsIgnoreCase(request.side())&&!"SELL".equalsIgnoreCase(request.side()))throw new IllegalArgumentException("주문 구분은 BUY 또는 SELL이어야 합니다.");
        if(request.orderType()!=null&&!request.orderType().isBlank()&&!"MARKET".equalsIgnoreCase(request.orderType())&&!"LIMIT".equalsIgnoreCase(request.orderType()))throw new IllegalArgumentException("주문 유형은 MARKET 또는 LIMIT이어야 합니다.");
    }
    @PreDestroy public void close(){running.set(false);MatchingEngine.executorShutdown(admission);}
}
