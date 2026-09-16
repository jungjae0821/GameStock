package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.gamestock.backend.market.MarketModels.*;

@RestController
@RequestMapping("/api")
public class MarketController {
    private final MarketService market;
    private final AuthService auth;

    public MarketController(MarketService market, AuthService auth) { this.market = market; this.auth = auth; }

    @GetMapping("/health") public Map<String, String> health() { return Map.of("status", "ok"); }
    @GetMapping("/stocks") public java.util.List<Stock> stocks() { return market.stocks(); }
    @GetMapping("/market-events") public java.util.List<MarketEvent> events() { return market.marketEvents(); }
    @GetMapping("/market-status") public MarketStatus marketStatus() { return market.marketStatus(); }
    @GetMapping("/ranking") public java.util.List<RankingEntry> ranking() { return market.ranking(); }
    @GetMapping("/stocks/{stockCode}/news")
    public java.util.List<MarketEvent> stockNews(@PathVariable String stockCode) {
        return market.stockNews(stockCode);
    }
    @GetMapping("/portfolio") public Portfolio portfolio(@RequestHeader(value = "Authorization", required = false) String authorization) { return market.portfolio(auth.requireUser(authorization).id()); }
    @GetMapping("/orders")
    public java.util.List<ActiveOrder> activeOrders(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return market.activeOrders(auth.requireUser(authorization).id());
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
        return auth.profile(auth.requireUser(authorization).id());
    }

    @PatchMapping("/profile")
    public AuthService.Profile updateProfile(@RequestBody AuthService.ProfileUpdate update,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.updateProfile(auth.requireUser(authorization).id(), update);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResult order(@Valid @RequestBody OrderRequest request, @RequestHeader(value = "Authorization", required = false) String authorization) { return market.order(request, auth.requireUser(authorization).id()); }

    @DeleteMapping("/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable long orderId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        market.cancelOrder(orderId, auth.requireUser(authorization).id());
    }

    @ExceptionHandler({IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> invalidOrder(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
