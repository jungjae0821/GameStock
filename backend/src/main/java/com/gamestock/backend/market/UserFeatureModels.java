package com.gamestock.backend.market;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class UserFeatureModels {
    private UserFeatureModels() { }

    public record WatchlistEntry(String stockCode, String addedAt) { }
    public record PriceAlert(long id, String stockCode, long targetPrice, boolean active, String createdAt, String triggeredAt) { }
    public record StockComment(long id, String stockCode, String nickname, String text, String createdAt) { }
    public record StockTag(String stockCode, String tag, String createdAt) { }
    public record CommentRequest(@NotBlank String text) { }
    public record TagRequest(@NotBlank String tag) { }
    public record PriceAlertRequest(@NotBlank String stockCode, @Min(1) @Max(1000000000L) long targetPrice) { }
}
