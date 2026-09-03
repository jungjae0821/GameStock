package com.gamestock.backend.market;

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

    public MarketController(MarketService market) { this.market = market; }

    @GetMapping("/health") public Map<String, String> health() { return Map.of("status", "ok"); }
    @GetMapping("/stocks") public java.util.List<Stock> stocks() { return market.stocks(); }
    @GetMapping("/market-events") public java.util.List<MarketEvent> events() { return market.marketEvents(); }
    @GetMapping("/portfolio") public Portfolio portfolio() { return market.portfolio(); }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResult order(@Valid @RequestBody OrderRequest request) { return market.order(request); }

    @ExceptionHandler({IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> invalidOrder(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
