package com.gamestock.backend.market;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

/** Each bot draws its own next action time after completing its previous action. */
@Component
public class BotTradingScheduler {
    private static final Logger log = LoggerFactory.getLogger(BotTradingScheduler.class);
    private static final long LIQUIDITY_MIN_DELAY_MS = 3_000;
    private static final long LIQUIDITY_MAX_DELAY_MS = 18_000;
    private static final long PARTICIPANT_MIN_DELAY_MS = 4_000;
    private static final long PARTICIPANT_MAX_DELAY_MS = 20_000;
    private final MarketService market;
    private final TaskScheduler scheduler;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

    public BotTradingScheduler(MarketService market, TaskScheduler scheduler) {
        this.market = market;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!tasks.isEmpty()) return;
        for (String code : market.botStockCodes()) {
            for (String side : List.of("BUY", "SELL")) {
                schedule("liquidity:" + code + ":" + side,
                        () -> market.liquidityBotAction(code, side), LIQUIDITY_MIN_DELAY_MS, LIQUIDITY_MAX_DELAY_MS);
            }
        }
        for (String username : market.participantBotUsernames()) {
            schedule(username, () -> market.participantBotAction(username), PARTICIPANT_MIN_DELAY_MS, PARTICIPANT_MAX_DELAY_MS);
        }
        log.info("Started {} independent bot schedules: liquidity 3-18s, participants 4-20s", tasks.size());
    }

    private void schedule(String name, Runnable action, long minimumMs, long maximumMs) {
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            try {
                action.run();
            } catch (RuntimeException error) {
                // A failed transaction rolls back; this bot still gets its next decision time.
                log.warn("Bot action failed for {}", name, error);
            }
        }, context -> {
            Instant base = context.lastCompletion();
            if (base == null) base = context.getClock().instant();
            return base.plusMillis(ThreadLocalRandom.current().nextLong(minimumMs, maximumMs + 1));
        });
        if (task != null) tasks.add(task);
    }

    @PreDestroy
    public synchronized void stop() {
        for (ScheduledFuture<?> task : tasks) task.cancel(false);
        tasks.clear();
    }
}
