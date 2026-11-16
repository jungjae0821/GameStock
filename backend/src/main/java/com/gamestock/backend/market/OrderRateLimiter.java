package com.gamestock.backend.market;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Per-account token buckets for orders placed over the web API.
 * Every human order shares one admission queue in {@link OrderService}, so without this a single
 * account could fill that queue and push other players' orders out. Internal callers (bots, stop
 * orders, loan liquidation) go to OrderService directly and are not limited here.
 */
@Component
public class OrderRateLimiter {
    private static final int MAX_ACCOUNTS = 20_000;

    private final Limit submit;
    private final Limit cancel;
    private final LongSupplier nanoTime;
    private final Map<String, Bucket> buckets = new HashMap<>();

    /** 생성자가 둘이라 Spring이 고를 수 있게 명시한다. 빠지면 기동 중 "No default constructor found"로 서버가 뜨지 않는다. */
    @Autowired
    public OrderRateLimiter(@Value("${market.order-limit.submit-burst:20}") int submitBurst,
                            @Value("${market.order-limit.submit-per-second:2}") double submitPerSecond,
                            @Value("${market.order-limit.cancel-burst:60}") int cancelBurst,
                            @Value("${market.order-limit.cancel-per-second:10}") double cancelPerSecond) {
        this(new Limit(submitBurst, submitPerSecond), new Limit(cancelBurst, cancelPerSecond), System::nanoTime);
    }

    OrderRateLimiter(Limit submit, Limit cancel, LongSupplier nanoTime) {
        if (submit.burst() < 1 || cancel.burst() < 1 || submit.perSecond() <= 0 || cancel.perSecond() <= 0)
            throw new IllegalArgumentException("Order limits must be positive");
        this.submit = submit;
        this.cancel = cancel;
        this.nanoTime = nanoTime;
    }

    public void submit(long userId) { take("submit:" + userId, submit); }
    public void cancel(long userId) { take("cancel:" + userId, cancel); }

    private synchronized void take(String key, Limit limit) {
        long now = nanoTime.getAsLong();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= MAX_ACCOUNTS) evictFull(now);
            bucket = new Bucket(limit.burst(), now);
            buckets.put(key, bucket);
        }
        bucket.refill(limit, now);
        if (bucket.tokens < 1) {
            double secondsUntilNext = (1 - bucket.tokens) / limit.perSecond();
            throw new Limited((long) Math.max(1, Math.ceil(secondsUntilNext)));
        }
        bucket.tokens -= 1;
    }

    /** A full bucket carries no state worth keeping: recreating it later gives the same answer. */
    private void evictFull(long now) {
        buckets.entrySet().removeIf(entry -> {
            Limit limit = entry.getKey().startsWith("submit:") ? submit : cancel;
            entry.getValue().refill(limit, now);
            return entry.getValue().tokens >= limit.burst();
        });
    }

    record Limit(int burst, double perSecond) { }

    private static final class Bucket {
        double tokens;
        long updatedAt;
        Bucket(double tokens, long updatedAt) { this.tokens = tokens; this.updatedAt = updatedAt; }
        void refill(Limit limit, long now) {
            double elapsed = Math.max(0, now - updatedAt) / 1_000_000_000.0;
            tokens = Math.min(limit.burst(), tokens + elapsed * limit.perSecond());
            updatedAt = now;
        }
    }

    public static final class Limited extends RuntimeException {
        private final long retryAfterSeconds;
        Limited(long retryAfterSeconds) {
            super("주문 요청이 너무 잦습니다. " + retryAfterSeconds + "초 후에 다시 시도해 주세요.");
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }
}
