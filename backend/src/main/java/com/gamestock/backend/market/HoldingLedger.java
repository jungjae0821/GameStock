package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Per-account share holdings split out of {@link MarketService}: reading a holding,
 * applying one fill to it, and rebuilding it from the trade ledger after a settlement
 * is cancelled. Not a Spring bean and keeps no state; callers already hold the market
 * monitor and transaction. {@link MarketService.HoldingState} stays on MarketService
 * because the liquidity provider and bot research read it too.
 */
final class HoldingLedger {
    private final JdbcTemplate jdbc;

    HoldingLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    MarketService.HoldingState state(long stockId, long userId) {
        return jdbc.query("""
                SELECT quantity, COALESCE(settled_quantity, quantity), average_price,
                       COALESCE(realized_profit_loss, 0)
                FROM portfolios WHERE user_id = ? AND stock_id = ?
                """, (rs, row) -> new MarketService.HoldingState(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)), userId, stockId)
                .stream().findFirst().orElse(new MarketService.HoldingState(0, 0, 0, 0));
    }

    void adjust(long stockId, long userId, int amount, long price, long fee, boolean buy, MarketService.HoldingState state) {
        int next = buy ? state.quantity() + amount : state.quantity() - amount;
        if (next < 0) throw new IllegalArgumentException("보유 수량이 부족합니다.");
        long gross = price * (long) amount;
        long nextAverage = buy && next > 0
                ? Math.round(((double) state.averagePrice() * state.quantity() + gross + fee) / next)
                : (next == 0 ? 0 : state.averagePrice());
        int nextSettled = buy ? Math.min(next, state.settledQuantity() + amount)
                : Math.max(0, state.settledQuantity() - amount);
        long nextRealized = buy ? state.realizedProfitLoss()
                : state.realizedProfitLoss() + gross - fee - state.averagePrice() * (long) amount;
        jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE quantity=VALUES(quantity), settled_quantity=VALUES(settled_quantity),
                        average_price=VALUES(average_price), realized_profit_loss=VALUES(realized_profit_loss)
                    """, userId, stockId, next, nextSettled, nextAverage, nextRealized);
    }

    void rebuildFromLedger(long stockId, long userId) {
        List<HoldingSnapshot> snapshots = jdbc.query("""
                SELECT settlements.trade_id, settlements.buyer_id, buyer_quantity_before, buyer_settled_quantity_before,
                       buyer_average_price_before, buyer_realized_profit_loss_before,
                       seller_quantity_before, seller_settled_quantity_before,
                       seller_average_price_before, seller_realized_profit_loss_before
                FROM settlements
                JOIN trades t0 ON t0.id = settlements.trade_id
                WHERE settlements.stock_id = ? AND (settlements.buyer_id = ? OR settlements.seller_id = ?)
                  AND t0.created_at > COALESCE((SELECT account_reset_at FROM users WHERE id = ?), '1970-01-01')
                  AND buyer_quantity_before IS NOT NULL
                  AND buyer_settled_quantity_before IS NOT NULL
                  AND buyer_average_price_before IS NOT NULL
                  AND buyer_realized_profit_loss_before IS NOT NULL
                  AND seller_quantity_before IS NOT NULL
                  AND seller_settled_quantity_before IS NOT NULL
                  AND seller_average_price_before IS NOT NULL
                  AND seller_realized_profit_loss_before IS NOT NULL
                ORDER BY trade_id ASC
                LIMIT 1
                """, (rs, row) -> {
            long tradeId = rs.getLong("trade_id");
            boolean buyer = rs.getLong("buyer_id") == userId;
            MarketService.HoldingState state = buyer
                    ? new MarketService.HoldingState(rs.getInt("buyer_quantity_before"), rs.getInt("buyer_settled_quantity_before"),
                    rs.getLong("buyer_average_price_before"), rs.getLong("buyer_realized_profit_loss_before"))
                    : new MarketService.HoldingState(rs.getInt("seller_quantity_before"), rs.getInt("seller_settled_quantity_before"),
                    rs.getLong("seller_average_price_before"), rs.getLong("seller_realized_profit_loss_before"));
            return new HoldingSnapshot(tradeId, state);
        }, stockId, userId, userId, userId);
        if (snapshots.isEmpty()) throw new IllegalArgumentException("거래 원장을 복구할 기준을 찾을 수 없습니다.");
        MarketService.HoldingState state = snapshots.get(0).state();
        List<LedgerTrade> trades = jdbc.query("""
                SELECT t.id, t.buyer_id, t.seller_id, t.quantity, t.price,
                       t.buyer_fee, t.seller_fee, st.status
                FROM trades t
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE t.stock_id = ? AND (t.buyer_id = ? OR t.seller_id = ?)
                  AND t.created_at > COALESCE((SELECT account_reset_at FROM users WHERE id = ?), '1970-01-01')
                  AND t.id >= ? AND (st.id IS NULL OR st.status <> 'CANCELLED')
                ORDER BY t.id ASC
                """, (rs, row) -> new LedgerTrade(rs.getLong("id"), rs.getLong("buyer_id"),
                rs.getLong("seller_id"), rs.getInt("quantity"), rs.getLong("price"),
                rs.getLong("buyer_fee"), rs.getLong("seller_fee"), rs.getString("status")),
                stockId, userId, userId, userId, snapshots.get(0).tradeId());
        for (LedgerTrade trade : trades) {
            boolean buy = trade.buyerId() == userId;
            long fee = buy ? trade.buyerFee() : trade.sellerFee();
            long gross = trade.price() * (long) trade.quantity();
            int nextQuantity = buy ? state.quantity() + trade.quantity() : state.quantity() - trade.quantity();
            if (nextQuantity < 0) throw new IllegalArgumentException("거래 원장을 복구할 수 없습니다.");
            long nextAverage = buy && nextQuantity > 0
                    ? Math.round(((double) state.averagePrice() * state.quantity() + gross + fee) / nextQuantity)
                    : (nextQuantity == 0 ? 0 : state.averagePrice());
            int nextSettled = buy ? state.settledQuantity()
                    : Math.max(0, state.settledQuantity() - trade.quantity());
            long nextRealized = buy ? state.realizedProfitLoss()
                    : state.realizedProfitLoss() + gross - fee - state.averagePrice() * (long) trade.quantity();
            if (buy && (trade.settlementStatus() == null || "SETTLED".equals(trade.settlementStatus())))
                nextSettled = Math.min(nextQuantity, nextSettled + trade.quantity());
            state = new MarketService.HoldingState(nextQuantity, nextSettled, nextAverage, nextRealized);
        }
        restore(stockId, userId, state.quantity(), state.settledQuantity(),
                state.averagePrice(), state.realizedProfitLoss());
    }

    private void restore(long stockId, long userId, int quantity, int settledQuantity,
                         long averagePrice, long realizedProfitLoss) {
        int updated = jdbc.update("""
                UPDATE portfolios SET quantity = ?, settled_quantity = ?, average_price = ?, realized_profit_loss = ?
                WHERE user_id = ? AND stock_id = ?
                """, quantity, settledQuantity, averagePrice, realizedProfitLoss, userId, stockId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, userId, stockId, quantity, settledQuantity, averagePrice, realizedProfitLoss);
        }
    }

    private record HoldingSnapshot(long tradeId, MarketService.HoldingState state) { }
    private record LedgerTrade(long id, long buyerId, long sellerId, int quantity, long price,
                               long buyerFee, long sellerFee, String settlementStatus) { }
}
