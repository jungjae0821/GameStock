package com.gamestock.backend.market;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;


/** Each bot draws its own next action time after completing its previous action. */
@Component
public class BotTradingScheduler {
    private static final Logger log = LoggerFactory.getLogger(BotTradingScheduler.class);
    private final MarketService market;
    private final TaskScheduler scheduler;
    private OrderService orders;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

    public BotTradingScheduler(MarketService market, TaskScheduler scheduler) {
        this.market = market;
        this.scheduler = scheduler;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BotTradingScheduler(MarketService market,TaskScheduler scheduler,OrderService orders) {
        this(market,scheduler);this.orders=orders;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!tasks.isEmpty() || !market.matchingEnabled()) return;
        market.maintainScheduledMarket();
        for (String code : market.botStockCodes()) {
            if(market.batchBotsEnabled()&&market.activeSimulation())market.liquidityBotAction(code,"BOTH");
            if(!market.inlineLiquidity())schedule("liquidity:" + code, () -> market.liquidityBotAction(code, "BOTH"));
        }
        if(market.batchBotsEnabled()) {
            for(int i=0;i<market.batchShardCount();i++) {
                final int shard=i;
                ScheduledFuture<?> task=scheduler.schedule(()->{
                    try{if(market.botWorkDue("batch:"+shard)){
                        if(orders==null)market.participantBatch(shard);
                        else orders.submitBotBatch(shard).exceptionally(error->{log.warn("Bot batch failed for shard {}",shard,error);return null;});
                    }}
                    catch(org.springframework.dao.CannotAcquireLockException retry){log.debug("Retrying funded bot decisions for shard {}",shard);}
                    catch(RuntimeException error){log.error("Participant batch rolled back for shard "+shard,error);}
                },context->{
                    Instant finished=context.lastCompletion(),began=context.lastActualExecution();
                    if(finished==null||began==null)return context.getClock().instant();
                    Instant due=began.plusMillis(market.participantCadenceMillis());
                    return due.isAfter(finished)?due:finished.plusMillis(5);
                });
                if(task!=null)tasks.add(task);
            }
        } else for (String username : market.participantBotUsernames()) {
            schedule(username, () -> market.participantBotAction(username));
        }
        log.info("Started {} bot tasks for {} participants and {} matching groups",tasks.size(),market.participantBotUsernames().size(),market.batchShardCount());
    }

    @Scheduled(fixedDelay=500)
    public void maintain() {
        try {market.maintainScheduledMarket();}
        catch(RuntimeException error){log.error("Scheduled market maintenance failed",error);}
    }

    private void schedule(String name, Runnable action) {
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            try {
                if(market.claimLegacyBotWork(name))action.run();
            } catch (RuntimeException error) {
                log.warn("Bot action failed for {}", name, error);
            }
        }, context -> {
            Instant base = context.lastCompletion();
            if (base == null) base = context.getClock().instant();
            return base.plusMillis(market.activeSimulation()?market.nextBotDelay(name):market.participantCadenceMillis());
        });
        if (task != null) tasks.add(task);
    }

    @PreDestroy
    public synchronized void stop() {
        for (ScheduledFuture<?> task : tasks) task.cancel(false);
        tasks.clear();
    }
}
