package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderRateLimiterTest {
    private final AtomicLong now = new AtomicLong();
    private final OrderRateLimiter limits = new OrderRateLimiter(
            new OrderRateLimiter.Limit(3, 1), new OrderRateLimiter.Limit(2, 1), now::get);

    @Test void burstIsAllowedThenRefillsOverTime() {
        for (int i = 0; i < 3; i++) limits.submit(1);
        var limited = assertThrows(OrderRateLimiter.Limited.class, () -> limits.submit(1));
        assertEquals(1, limited.retryAfterSeconds());

        now.addAndGet(1_000_000_000L);
        limits.submit(1);
        assertThrows(OrderRateLimiter.Limited.class, () -> limits.submit(1));
    }

    @Test void oneAccountCannotSpendAnotherAccountsBudget() {
        for (int i = 0; i < 3; i++) limits.submit(1);
        assertThrows(OrderRateLimiter.Limited.class, () -> limits.submit(1));
        assertDoesNotThrow(() -> limits.submit(2));
    }

    @Test void cancelsHaveTheirOwnBudget() {
        for (int i = 0; i < 3; i++) limits.submit(1);
        limits.cancel(1);
        limits.cancel(1);
        assertThrows(OrderRateLimiter.Limited.class, () -> limits.cancel(1));
    }

    @Test void webOrderOverTheLimitGets429WithMessageAndRetryAfter() throws Exception {
        var auth = mock(AuthService.class);
        var orders = mock(OrderService.class);
        when(auth.requireUser(any())).thenReturn(new AuthService.LoginUser(7, "player", "p@example.test", null, 0, 0, false, "USER"));
        when(orders.submit(anyLong(), any())).thenReturn(CompletableFuture.completedFuture(null));
        var controller = new MarketController(mock(MarketService.class), auth, mock(UserFeatureService.class), orders,
                new MarketMetrics(), mock(BotTradingScheduler.class), mock(PublicMarketCache.class));
        controller.configureOrderLimits(limits);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String body = "{\"stockCode\":\"GI\",\"side\":\"SELL\",\"quantity\":1,\"orderType\":\"MARKET\"}";

        for (int i = 0; i < 3; i++)
            mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body));
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                // Accept 헤더 없는 클라이언트도 JSON을 받아야 한다. XML 변환 라이브러리가 끼어들면 여기서 잡힌다.
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("주문 요청이 너무 잦습니다")));
        verify(orders, times(3)).submit(eq(7L), any());
    }
}
