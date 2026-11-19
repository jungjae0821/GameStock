package com.gamestock.backend.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.auth.AuthService;
import com.gamestock.backend.market.DerivativeService;
import com.gamestock.backend.market.MarketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.gamestock.backend.openapi.OpenApiModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OpenApiControllerTest {
    private final OpenApiKeyService keys = mock(OpenApiKeyService.class);
    private final OpenApiTradingService trading = mock(OpenApiTradingService.class);
    private final OpenApiQuoteService quotes = mock(OpenApiQuoteService.class);
    private final MarketService market = mock(MarketService.class);
    private final DerivativeService derivatives = mock(DerivativeService.class);
    private final AuthService auth = mock(AuthService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var limiter = new OpenApiRateLimiter();
        mvc = MockMvcBuilders.standaloneSetup(new OpenApiController(keys, limiter, quotes, trading, market, derivatives, mock(OpenApiOrderQuery.class)),
                        new OpenApiKeyController(auth, keys, limiter))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        OpenApiWebConfig.strictMapper(new ObjectMapper())))
                .setControllerAdvice(new OpenApiErrors()).build();
        when(keys.authenticate("Bearer test", true)).thenReturn(new Principal("key", 7, Permission.TRADE));
    }

    @Test void publicPricesDoNotNeedCredentials() throws Exception {
        when(quotes.stocks()).thenReturn(List.of());
        mvc.perform(get("/api/openapi/v1/stocks")).andExpect(status().isOk()).andExpect(content().json("[]"));
        verifyNoInteractions(keys, trading);
    }

    @Test void accountUsesOnlyTheOwnerFromTheCredential() throws Exception {
        when(keys.authenticate("Bearer read", false)).thenReturn(new Principal("read", 42, Permission.READ));
        when(derivatives.account(42)).thenReturn(new com.gamestock.backend.market.DerivativeModels.Account(0,0,0,0,0,0,List.of(),List.of()));
        mvc.perform(get("/api/openapi/v1/account").header("Authorization", "Bearer read").param("userId", "999"))
                .andExpect(status().isOk());
        verify(market).portfolio(42);
        verify(derivatives).account(42);
    }

    @Test void readOnlyKeyCannotReachTrading() throws Exception {
        when(keys.authenticate("Bearer read", true)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "read only"));
        mvc.perform(post("/api/openapi/v1/orders").header("Authorization", "Bearer read")
                        .header("Idempotency-Key", "stock-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"STOCK\",\"side\":\"BUY\",\"quantity\":1,\"symbol\":\"GI\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(trading);
    }

    @Test void quantitiesUnknownFieldsAndMalformedEnumsFailBeforeExecution() throws Exception {
        for (String body : List.of(
                "{\"product\":\"STOCK\",\"side\":\"BUY\",\"quantity\":0,\"symbol\":\"GI\"}",
                "{\"product\":\"STOCK\",\"side\":\"BUY\",\"quantity\":1.5,\"symbol\":\"GI\"}",
                "{\"product\":0,\"side\":\"BUY\",\"quantity\":1,\"symbol\":\"GI\"}",
                "{\"product\":\"STOCK\",\"side\":\"BUY\",\"quantity\":1,\"symbol\":\"GI\",\"prcie\":1000}",
                "{\"product\":\"STOCK\",\"side\":\"CLOSE\",\"quantity\":1,\"symbol\":\"GI\"}")) {
            mvc.perform(post("/api/openapi/v1/orders").header("Authorization", "Bearer test")
                            .header("Idempotency-Key", "test-1").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(trading);
        mvc.perform(get("/api/openapi/v1/options").param("type", "WRONG"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/openapi/v1/orders/not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test void asyncOrdersReturnDurableReceipt() throws Exception {
        var receipt = new Receipt("stock-1", "ORDER", new ObjectMapper().readTree("{\"orderId\":123,\"status\":\"OPEN\"}"));
        when(trading.order(any(), eq("stock-1"), any())).thenReturn(CompletableFuture.completedFuture(receipt));
        var result = mvc.perform(post("/api/openapi/v1/orders").header("Authorization", "Bearer test")
                        .header("Idempotency-Key", "stock-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"STOCK\",\"side\":\"BUY\",\"quantity\":1,\"symbol\":\"GI\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("stock-1")).andExpect(jsonPath("$.result.orderId").value(123));
    }

    @Test void asyncConflictsHaveStableErrorShape() throws Exception {
        when(trading.cancel(any(), eq("same-key"), eq(1L))).thenReturn(CompletableFuture.failedFuture(
                new ResponseStatusException(HttpStatus.CONFLICT, "different body")));
        var result = mvc.perform(delete("/api/openapi/v1/orders/1").header("Authorization", "Bearer test")
                        .header("Idempotency-Key", "same-key")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test void keyIssuanceRequiresFirebaseAndAdminCannotCreateTradingKey() throws Exception {
        when(auth.requireMember("Bearer firebase")).thenReturn(new AuthService.LoginUser(7, "admin", null, null, 0, 0, false, "ADMIN"));
        mvc.perform(post("/api/openapi/keys").header("Authorization", "Bearer firebase")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"bot\",\"permission\":\"TRADE\"}"))
                .andExpect(status().isForbidden());
        verify(keys, never()).create(anyLong(), any());
    }

    @Test void rateLimitsAreSharedAcrossKeysAndResetAfterWindow() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000L);
        var limiter = new OpenApiRateLimiter(clock);
        for (int i = 0; i < 30; i++) limiter.account(7, true);
        var failure = assertThrows(OpenApiRateLimiter.RateLimited.class, () -> limiter.account(7, true));
        assertEquals(60, failure.retryAfter);
        limiter.account(8, true);
        when(clock.millis()).thenReturn(61_000L);
        limiter.account(7, true);
    }

    @Test void rateLimitResponseIncludesRetryAfter() throws Exception {
        when(keys.authenticate(null, false)).thenThrow(new OpenApiRateLimiter.RateLimited(12));
        mvc.perform(get("/api/openapi/v1/account")).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12")).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
