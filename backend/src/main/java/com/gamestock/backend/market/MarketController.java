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
    @GetMapping("/stocks/{stockCode}/news")
    public java.util.List<MarketEvent> stockNews(@PathVariable String stockCode) {
        return market.stockNews(stockCode);
    }
    @GetMapping("/portfolio") public Portfolio portfolio(@RequestHeader(value = "Authorization", required = false) String authorization) { return market.portfolio(auth.requireUser(authorization).id()); }
    @GetMapping("/orders/{stockCode}")
    public java.util.List<OrderHistory> orderHistory(@PathVariable String stockCode, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return market.orderHistory(stockCode, auth.requireUser(authorization).id());
    }
    @GetMapping("/stocks/{stockCode}/trades")
    public java.util.List<PublicTrade> publicTrades(@PathVariable String stockCode) { return market.publicTrades(stockCode); }
    @GetMapping({"/orderbook/{stockCode}", "/stocks/{stockCode}/orderbook"})
    public OrderBook orderBook(@PathVariable String stockCode) { return market.orderBook(stockCode); }
    @GetMapping("/stocks/{stockCode}/history")
    public java.util.List<PricePoint> priceHistory(@PathVariable String stockCode) {
        return market.priceHistory(stockCode);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResult order(@Valid @RequestBody OrderRequest request, @RequestHeader(value = "Authorization", required = false) String authorization) { return market.order(request, auth.requireUser(authorization).id()); }

    @ExceptionHandler({IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> invalidOrder(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
