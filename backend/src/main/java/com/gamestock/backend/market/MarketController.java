package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.gamestock.backend.market.MarketModels.*;
import static com.gamestock.backend.market.UserFeatureModels.*;

@RestController
@RequestMapping("/api")
public class MarketController {
    private final MarketService market;
    private final AuthService auth;
    private final UserFeatureService userFeatures;
    private final OrderService orders;
    private final MarketMetrics metrics;
    private final BotTradingScheduler scheduler;
    private final PublicMarketCache publicCache;

    private CareerService career;

    /** Optional so tests that build this class by hand keep working without the career's loss limits. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureCareer(CareerService career) { this.career = career; }

    public MarketController(MarketService market, AuthService auth, UserFeatureService userFeatures,OrderService orders,MarketMetrics metrics,BotTradingScheduler scheduler,PublicMarketCache publicCache) { this.market = market; this.auth = auth; this.userFeatures = userFeatures;this.orders=orders;this.metrics=metrics;this.scheduler=scheduler;this.publicCache=publicCache; }

    @GetMapping("/health") public Map<String, Object> health() {
        return Map.of("status","ok","matchingEnabled",market.matchingEnabled(),
                "batchBotsEnabled",market.batchBotsEnabled(),"inlineLiquidity",market.inlineLiquidity(),
                "batchShards",market.batchShardCount(),"botScheduler",scheduler.runtimeStatus());
    }
    @GetMapping("/stocks") public java.util.List<Stock> stocks() { return publicCache.stocks(); }
    @GetMapping("/market-events") public java.util.List<MarketEvent> events() { return publicCache.marketEvents(); }
    @GetMapping("/market-status") public MarketStatus marketStatus() { return publicCache.marketStatus(); }
    @GetMapping("/ranking") public java.util.List<RankingEntry> ranking() { return market.ranking(); }
    @GetMapping("/ranking/wealth") public java.util.List<MarketModels.WealthEntry> wealthRanking() { return market.wealthRanking(); }
    @GetMapping("/stocks/{stockCode}/news")
    public java.util.List<MarketEvent> stockNews(@PathVariable String stockCode) {
        return market.stockNews(stockCode);
    }
    @GetMapping("/portfolio") public Portfolio portfolio(@RequestHeader(value = "Authorization", required = false) String authorization) { return market.portfolio(auth.requireUser(authorization).id()); }
    @GetMapping("/missions")
    public DailyMissionStatus missions(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return market.dailyMissions(auth.requireUser(authorization).id());
    }
    @PostMapping("/missions/{missionId}/reward")
    public MissionRewardResult missionReward(@PathVariable String missionId,
                                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        return market.rewardMission(missionId, auth.requireUser(authorization).id());
    }
    @GetMapping("/orders")
    public java.util.List<ActiveOrder> activeOrders(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestParam(required = false) String stockCode) {
        return market.activeOrders(auth.requireUser(authorization).id(), stockCode == null ? null : stockCode.toUpperCase(java.util.Locale.ROOT));
    }
    @DeleteMapping("/orders")
    public java.util.concurrent.CompletableFuture<java.util.Map<String,Integer>> cancelAllOrders(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return orders.cancelAll(auth.requireUser(authorization).id()).thenApply(count -> java.util.Map.of("cancelled", count));
    }
    @GetMapping("/settlements")
    public java.util.List<SettlementEntry> settlements(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return market.settlements(auth.requireUser(authorization).id());
    }
    @DeleteMapping("/settlements/{settlementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelSettlement(@PathVariable long settlementId,
                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        market.cancelSettlement(settlementId, auth.requireUser(authorization).id());
    }
    @GetMapping("/orders/{stockCode}")
    public java.util.List<OrderHistory> orderHistory(@PathVariable String stockCode, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return market.orderHistory(stockCode, auth.requireUser(authorization).id());
    }
    @GetMapping("/stocks/{stockCode}/trades")
    public java.util.List<PublicTrade> publicTrades(@PathVariable String stockCode) { return market.publicTrades(stockCode); }
    @GetMapping({"/orderbook/{stockCode}", "/stocks/{stockCode}/orderbook"})
    public OrderBook orderBook(@PathVariable String stockCode) { return market.orderBook(stockCode); }
    @GetMapping("/stocks/{stockCode}/history")
    public java.util.List<PricePoint> priceHistory(@PathVariable String stockCode,
                                                   @RequestParam(defaultValue = "24h") String range) {
        return market.priceHistory(stockCode, range);
    }
    @GetMapping("/stocks/{stockCode}/chart")
    public java.util.List<ChartCandle> chart(@PathVariable String stockCode,
                                             @RequestParam(defaultValue = "1d") String range) {
        return market.chartHistory(stockCode, range);
    }
    @GetMapping("/stocks/{stockCode}/price-drivers")
    public PriceDrivers priceDrivers(@PathVariable String stockCode) {
        return market.priceDrivers(stockCode);
    }
    @GetMapping({"/stocks/{stockCode}/daily", "/stocks/{stockCode}/ohlcv"})
    public java.util.List<DailyCandle> dailySummaries(@PathVariable String stockCode) {
        return market.dailySummaries(stockCode);
    }

    @GetMapping("/profile")
    public AuthService.Profile profile(@RequestHeader(value = "Authorization", required = false) String authorization) {
        // 정지된 계정도 프로필은 받아야 화면에 정지 기간과 사유를 보여 줄 수 있다.
        return auth.profile(auth.requireUserAllowingSuspended(authorization).id());
    }

    @PatchMapping("/profile")
    public AuthService.Profile updateProfile(@RequestBody AuthService.ProfileUpdate update,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.updateProfile(auth.requireUser(authorization).id(), update);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public java.util.concurrent.CompletableFuture<OrderResult> order(@RequestBody OrderRequest request, @RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthService.LoginUser user = auth.requireUser(authorization);
        // 관리자는 시장 밖의 계정이다. 관리자 주문이 다른 플레이어의 시세와 수익률을 움직이지 않게 막는다.
        if ("ADMIN".equalsIgnoreCase(user.role()))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정은 주문할 수 없습니다.");
        // Buying adds risk and waits out a loss lock; selling never does.
        if (career != null && request != null && "BUY".equalsIgnoreCase(request.side()))
            career.requireNewRisk(user.id(), request.stockCode(), request.quantity(),
                    "LIMIT".equalsIgnoreCase(request.orderType()) ? request.price() : null);
        return orders.submit(user.id(), request);
    }

    @DeleteMapping("/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public java.util.concurrent.CompletableFuture<Void> cancelOrder(@PathVariable long orderId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return orders.cancel(auth.requireUser(authorization).id(),orderId);
    }

    @GetMapping("/market-metrics")
    public Map<String,Long> metrics(@RequestHeader(value="Authorization",required=false) String authorization){auth.requireAdmin(authorization);return metrics.snapshot();}

    // 372.ro식 관심·알림·커뮤니티 기능. 읽기 공개 범위는 기존 시세/뉴스와 동일하고,
    // 변경 작업은 Firebase로 인증된 사용자만 수행할 수 있다.
    @GetMapping("/watchlist")
    public java.util.List<WatchlistEntry> watchlist(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return userFeatures.watchlist(auth.requireUser(authorization).id());
    }

    @PutMapping("/watchlist/{stockCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addWatchlist(@PathVariable String stockCode, @RequestHeader(value = "Authorization", required = false) String authorization) {
        userFeatures.addWatchlist(auth.requireUser(authorization).id(), stockCode);
    }

    @DeleteMapping("/watchlist/{stockCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeWatchlist(@PathVariable String stockCode, @RequestHeader(value = "Authorization", required = false) String authorization) {
        userFeatures.removeWatchlist(auth.requireUser(authorization).id(), stockCode);
    }

    @GetMapping("/price-alerts")
    public java.util.List<PriceAlert> priceAlerts(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return userFeatures.alerts(auth.requireUser(authorization).id());
    }

    @PostMapping("/price-alerts")
    @ResponseStatus(HttpStatus.CREATED)
    public PriceAlert addPriceAlert(@Valid @RequestBody PriceAlertRequest request, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return userFeatures.addAlert(auth.requireUser(authorization).id(), request);
    }

    @DeleteMapping("/price-alerts/{alertId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePriceAlert(@PathVariable long alertId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        userFeatures.deleteAlert(auth.requireUser(authorization).id(), alertId);
    }

    @GetMapping("/stocks/{stockCode}/comments")
    public java.util.List<StockComment> comments(@PathVariable String stockCode) { return userFeatures.comments(stockCode); }

    @PostMapping("/stocks/{stockCode}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public StockComment addComment(@PathVariable String stockCode, @Valid @RequestBody CommentRequest request,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        return userFeatures.addComment(auth.requireUser(authorization).id(), stockCode, request);
    }

    @GetMapping("/stocks/{stockCode}/tags")
    public java.util.List<StockTag> tags(@PathVariable String stockCode) { return userFeatures.tags(stockCode); }

    @PostMapping("/stocks/{stockCode}/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public StockTag addTag(@PathVariable String stockCode, @Valid @RequestBody TagRequest request,
                           @RequestHeader(value = "Authorization", required = false) String authorization) {
        return userFeatures.addTag(auth.requireUser(authorization).id(), stockCode, request);
    }

    @ExceptionHandler({IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> invalidOrder(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
