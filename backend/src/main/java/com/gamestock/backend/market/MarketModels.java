package com.gamestock.backend.market;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class MarketModels {
    private MarketModels() { }

    public record Stock(String code, String name, String genre, long price, double changePercent, long volume) { }
    /**
     * cash is immediately available cash. The unsettled fields remain in the
     * response for client compatibility, but immediate settlement keeps them at
     * zero for newly executed trades.
     */
    public record Portfolio(long cash, long assetValue, long totalAsset, List<Position> positions,
                            long unsettledCash, long unsettledAssetValue, long totalFees,
                            long realizedProfitLoss) {
        public Portfolio(long cash, long assetValue, long totalAsset, List<Position> positions) {
            this(cash, assetValue, totalAsset, positions, 0, 0, 0, 0);
        }
    }

    /**
     * profitLoss/profitLossPercent are the unrealized (valuation) result kept
     * under the original field names for web/mobile compatibility.  realized
     * results are accumulated independently as shares are sold.
     */
    public record Position(String stockCode, int quantity, int settledQuantity, int unsettledQuantity,
                           long averagePrice, long marketValue, long profitLoss, double profitLossPercent,
                           long realizedProfitLoss) {
        public Position(String stockCode, int quantity, long averagePrice, long marketValue,
                        long profitLoss, double profitLossPercent) {
            this(stockCode, quantity, quantity, 0, averagePrice, marketValue, profitLoss,
                    profitLossPercent, 0);
        }
    }
    public record RankingEntry(int rank, String nickname, String profileImageUrl, long totalAsset, long assetValue, long cash, double changePercent) { }
    public record MarketEvent(String stockCode, String title, int impact, String sentiment) { }
    public record MarketSnapshot(List<Stock> stocks, Portfolio portfolio, List<MarketEvent> events) { }
    public record OrderRequest(@NotBlank String stockCode, @NotBlank String side, @Min(1) @Max(1000) int quantity, String orderType, Long price) { }
    public record OrderResult(String message, String stockCode, String side, int quantity, long price,
                              String status, Portfolio portfolio, long fee, String settlementStatus,
                              String settlementAt) {
        public OrderResult(String message, String stockCode, String side, int quantity, long price,
                           String status, Portfolio portfolio) {
            this(message, stockCode, side, quantity, price, status, portfolio, 0, "NONE", null);
        }
    }
    public record OrderHistory(String side, int quantity, long price, String status, String orderType,
                               int remainingQuantity, String createdAt, long fee, String settlementStatus,
                               String settlementAt) {
        public OrderHistory(String side, int quantity, long price, String status, String orderType,
                            int remainingQuantity, String createdAt) {
            this(side, quantity, price, status, orderType, remainingQuantity, createdAt, 0, "NONE", null);
        }
    }
    public record PublicTrade(String side, int quantity, long price, String orderType, String createdAt) { }
    public record ActiveOrder(long id, String stockCode, String side, int quantity, int remainingQuantity, long price,
                              String status, String orderType, long reservedCash, int reservedQuantity,
                              String createdAt, String expiresAt) { }
    public record OrderBookLevel(long price, int quantity, int orderCount) { }
    public record OrderBook(String stockCode, List<OrderBookLevel> bids, List<OrderBookLevel> asks) { }
    public record PricePoint(long price, String recordedAt) { }
    /** Daily open/close/volume summary. High/low are intentionally omitted per README scope. */
    public record DailyCandle(String stockCode, String tradingDate, long openPrice, long closePrice,
                              long volume) { }
    public record MarketStatus(boolean open, String session, String timezone, String message) { }
    public record SettlementEntry(long id, String side, String stockCode, int quantity, long grossAmount,
                                  long fee, long netAmount, String status, String settlementAt,
                                  String createdAt, boolean cancellable) {
        public SettlementEntry(long id, String side, String stockCode, int quantity, long grossAmount,
                                long fee, long netAmount, String status, String settlementAt,
                                String createdAt) {
            this(id, side, stockCode, quantity, grossAmount, fee, netAmount, status, settlementAt, createdAt, true);
        }
    }
}
