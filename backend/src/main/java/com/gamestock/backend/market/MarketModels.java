package com.gamestock.backend.market;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class MarketModels {
    private MarketModels() { }

    public record Stock(String code, String name, String genre, long price, double changePercent, long volume) { }
    public record Portfolio(long cash, long assetValue, long totalAsset, List<Position> positions) { }
    public record Position(String stockCode, int quantity, long marketValue) { }
    public record MarketEvent(String stockCode, String title, int impact, String sentiment) { }
    public record MarketSnapshot(List<Stock> stocks, Portfolio portfolio, List<MarketEvent> events) { }
    public record OrderRequest(@NotBlank String stockCode, @NotBlank String side, @Min(1) @Max(1000) int quantity) { }
    public record OrderResult(String message, String stockCode, String side, int quantity, long price, Portfolio portfolio) { }
    public record OrderHistory(String side, int quantity, long price, String status, String createdAt) { }
    public record PricePoint(long price, String recordedAt) { }
}
