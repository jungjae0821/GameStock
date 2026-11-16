package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/** 운영 기동처럼 Spring이 직접 만들 수 있어야 한다. 생성자가 둘이라 어느 쪽인지 명시가 필요했다. */
class OrderRateLimiterContextTest {
    @Test void springCanCreateTheLimiterWithDefaultSettings() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(OrderRateLimiter.class);
            context.refresh();
            var limiter = context.getBean(OrderRateLimiter.class);
            for (int i = 0; i < 20; i++) limiter.submit(1);
            assertThrows(OrderRateLimiter.Limited.class, () -> limiter.submit(1));
        }
    }
}
