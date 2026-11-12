package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;

import static com.gamestock.backend.market.MarketModels.*;

/**
 * Per-account read models split out of {@link MarketService}: open orders, recent fills,
 * order history, the fee and settlement summary of one order, and the portfolio valuation.
 * Not a Spring bean and keeps no state; MarketService keeps the transactional entry points
 * and delegates here.
 */
final class AccountQueries {
    private final MarketService market;
    private final JdbcTemplate jdbc;

    AccountQueries(MarketService market, JdbcTemplate jdbc) {
        this.market = market;
        this.jdbc = jdbc;
    }

    List<ActiveOrder> activeOrders(long userId, String stockCode) {
        return jdbc.query("""
                SELECT o.id, s.stock_code, o.side, o.quantity, o.remaining_quantity, COALESCE(o.price, 0) AS price,
                       o.status, o.order_type, COALESCE(o.reserved_cash, 0) AS reserved_cash,
                       COALESCE(o.reserved_quantity, 0) AS reserved_quantity, o.created_at, o.expires_at
                FROM orders o JOIN stocks s ON s.id = o.stock_id
                WHERE o.user_id = ? AND o.status = 'OPEN'
                  AND (? IS NULL OR s.stock_code = ?)
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 5
                """, (rs, row) -> new ActiveOrder(
                rs.getLong("id"), rs.getString("stock_code"), rs.getString("side"),
                rs.getInt("quantity"), rs.getInt("remaining_quantity"), rs.getLong("price"),
                rs.getString("status"), rs.getString("order_type"), rs.getLong("reserved_cash"),
                rs.getInt("reserved_quantity"), market.databaseInstant(rs.getTimestamp("created_at")).toString(),
                rs.getTimestamp("expires_at") == null ? null : market.databaseInstant(rs.getTimestamp("expires_at")).toString()), userId, stockCode, stockCode);
    }

    /** The user's recently completed fills. Cash and shares settle immediately. */
    List<SettlementEntry> settlements(long userId) {
        return jdbc.query("""
                SELECT x.id, x.side, s.stock_code, x.quantity, x.gross_amount, x.fee,
                       x.net_amount, x.status, x.settlement_at, x.created_at, x.cancellable
                FROM (
                  SELECT st.id, st.stock_id, st.quantity, st.gross_amount,
                         CASE WHEN st.buyer_id = ? THEN 'BUY' ELSE 'SELL' END AS side,
                         CASE WHEN st.buyer_id = ? THEN st.buyer_fee ELSE st.seller_fee END AS fee,
                         CASE WHEN st.buyer_id = ? THEN st.gross_amount + st.buyer_fee
                              ELSE st.gross_amount - st.seller_fee END AS net_amount,
                         st.status, st.settlement_at, st.created_at,
                         CASE WHEN st.status = 'PENDING'
                                    AND st.buyer_quantity_before IS NOT NULL
                                    AND st.buyer_settled_quantity_before IS NOT NULL
                                    AND st.buyer_average_price_before IS NOT NULL
                                    AND st.buyer_realized_profit_loss_before IS NOT NULL
                                    AND st.seller_quantity_before IS NOT NULL
                                    AND st.seller_settled_quantity_before IS NOT NULL
                                    AND st.seller_average_price_before IS NOT NULL
                                    AND st.seller_realized_profit_loss_before IS NOT NULL
                              THEN TRUE ELSE FALSE END AS cancellable
                  FROM settlements st JOIN users owner ON owner.id = ?
                  WHERE (st.buyer_id = ? OR st.seller_id = ?) AND st.status = 'SETTLED'
                    AND st.created_at > COALESCE(owner.account_reset_at, '1970-01-01')
                ) x JOIN stocks s ON s.id = x.stock_id
                ORDER BY x.created_at DESC, x.id DESC
                LIMIT 5
                """, (rs, row) -> new SettlementEntry(
                rs.getLong("id"), rs.getString("side"), rs.getString("stock_code"),
                rs.getInt("quantity"), rs.getLong("gross_amount"), rs.getLong("fee"),
                rs.getLong("net_amount"), rs.getString("status"),
                market.databaseInstant(rs.getTimestamp("settlement_at")).toString(),
                market.databaseInstant(rs.getTimestamp("created_at")).toString(),
                rs.getBoolean("cancellable")),
                userId, userId, userId, userId, userId, userId);
    }

    List<OrderHistory> orderHistory(String code, long userId) {
        return jdbc.query("""
                SELECT o.side, o.quantity, o.price, o.status, o.order_type, o.remaining_quantity, o.created_at,
                       COALESCE(SUM(CASE WHEN o.side = 'BUY' THEN t.buyer_fee ELSE t.seller_fee END), 0) AS fee,
                       CASE WHEN COUNT(t.id) = 0 THEN 'NONE'
                            WHEN SUM(CASE WHEN st.status = 'CANCELLED' THEN 1 ELSE 0 END) > 0 THEN 'CANCELLED'
                            WHEN SUM(CASE WHEN st.status = 'PENDING' THEN 1 ELSE 0 END) > 0 THEN 'PENDING'
                            ELSE 'SETTLED' END AS settlement_status,
                       MIN(CASE WHEN st.status = 'PENDING' THEN st.settlement_at ELSE NULL END) AS settlement_at
                FROM orders o JOIN stocks s ON s.id = o.stock_id
                LEFT JOIN trades t ON t.buy_order_id = o.id OR t.sell_order_id = o.id
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE o.user_id = ? AND s.stock_code = ?
                  AND o.created_at > COALESCE((SELECT account_reset_at FROM users WHERE id = ?), '1970-01-01')
                GROUP BY o.id, o.side, o.quantity, o.price, o.status, o.order_type, o.remaining_quantity, o.created_at
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 20
                """, (rs, row) -> new OrderHistory(
                rs.getString("side"),
                rs.getInt("quantity"),
                rs.getLong("price"),
                rs.getString("status"),
                rs.getString("order_type"),
                rs.getInt("remaining_quantity"),
                market.databaseInstant(rs.getTimestamp("created_at")).toString(),
                rs.getLong("fee"), rs.getString("settlement_status"),
                rs.getTimestamp("settlement_at") == null ? null : market.databaseInstant(rs.getTimestamp("settlement_at")).toString()),
                userId, code.toUpperCase(Locale.ROOT), userId);
    }

    ExecutionSummary executionSummary(long orderId, String side) {
        List<ExecutionSummary> rows = jdbc.query("""
                SELECT COALESCE(SUM(CASE WHEN ? = 'BUY' THEN t.buyer_fee ELSE t.seller_fee END), 0) AS fee,
                       CASE WHEN COUNT(t.id) = 0 THEN 'NONE'
                            WHEN SUM(CASE WHEN st.status = 'CANCELLED' THEN 1 ELSE 0 END) > 0 THEN 'CANCELLED'
                            WHEN SUM(CASE WHEN st.status = 'PENDING' THEN 1 ELSE 0 END) > 0 THEN 'PENDING'
                            ELSE 'SETTLED' END AS settlement_status,
                       MIN(CASE WHEN st.status = 'PENDING' THEN st.settlement_at ELSE NULL END) AS settlement_at
                FROM trades t
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE t.buy_order_id = ? OR t.sell_order_id = ?
                """, (rs, row) -> new ExecutionSummary(rs.getLong("fee"), rs.getString("settlement_status"),
                rs.getTimestamp("settlement_at") == null ? null : market.databaseInstant(rs.getTimestamp("settlement_at")).toString()),
                side, orderId, orderId);
        return rows.isEmpty() ? new ExecutionSummary(0, "NONE", null) : rows.get(0);
    }

    Portfolio portfolio(long userId) {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        List<Position> positions = jdbc.query("""
                SELECT s.stock_code, p.quantity, COALESCE(p.settled_quantity, p.quantity) AS settled_quantity,
                       COALESCE((SELECT SUM(o.reserved_quantity) FROM orders o
                                 WHERE o.user_id = p.user_id AND o.stock_id = p.stock_id
                                   AND o.side = 'SELL' AND o.status = 'OPEN'), 0) AS reserved_quantity,
                       p.average_price, COALESCE(p.realized_profit_loss, 0) AS realized_profit_loss,
                       p.quantity * COALESCE(pm.mark_price,s.current_price) AS market_value,
                       GREATEST(0, COALESCE(p.settled_quantity,p.quantity) - COALESCE((
                           SELECT SUM(o.reserved_quantity) FROM orders o
                           WHERE o.user_id = p.user_id AND o.stock_id = p.stock_id AND o.status = 'OPEN' AND o.side = 'SELL'
                       ),0)) AS available_quantity,
                       p.quantity * (COALESCE(pm.mark_price,s.current_price) - p.average_price) AS profit_loss
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                WHERE p.user_id = ? AND p.quantity > 0
                ORDER BY p.id
                """, (rs, row) -> new Position(rs.getString("stock_code"),
                rs.getInt("quantity"), rs.getInt("settled_quantity"),
                Math.max(0, rs.getInt("quantity") - rs.getInt("settled_quantity")),
                Math.max(0, rs.getInt("settled_quantity") - rs.getInt("reserved_quantity")),
                rs.getLong("average_price"), rs.getLong("market_value"), rs.getLong("profit_loss"),
                rs.getLong("average_price") == 0 ? 0 :
                        Math.round(rs.getLong("profit_loss") * 10000.0 /
                                (rs.getInt("quantity") * rs.getLong("average_price"))) / 100.0,
                rs.getLong("realized_profit_loss")), userId);
        long reservedCash = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_cash), 0) FROM orders WHERE user_id = ? AND status = 'OPEN'", Long.class, userId);
        long unsettledCash = jdbc.queryForObject("""
                SELECT COALESCE(SUM(gross_amount - seller_fee), 0)
                FROM settlements WHERE seller_id = ? AND status = 'PENDING'
                """, Long.class, userId);
        long unsettledAssetValue = positions.stream()
                .filter(position -> position.unsettledQuantity() > 0)
                .mapToLong(position -> (long) position.unsettledQuantity() * currentPrice(position.stockCode()))
                .sum();
        long totalFees = jdbc.queryForObject("""
                SELECT COALESCE(SUM(f.fee),0) FROM (
                  SELECT id,buyer_fee fee FROM trades WHERE buyer_id=?
                    AND created_at>COALESCE((SELECT account_reset_at FROM users WHERE id=?),'1970-01-01')
                  UNION ALL
                  SELECT id,seller_fee fee FROM trades WHERE seller_id=?
                    AND created_at>COALESCE((SELECT account_reset_at FROM users WHERE id=?),'1970-01-01')
                ) f WHERE NOT EXISTS (SELECT 1 FROM settlements st WHERE st.trade_id=f.id AND st.status='CANCELLED')
                """, Long.class, userId, userId, userId, userId);
        totalFees+=jdbc.queryForObject("SELECT COALESCE(SUM(fees),0) FROM bot_account_fees WHERE user_id=?",Long.class,userId);
        long realizedProfitLoss = jdbc.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(realized_profit_loss, 0)), 0)
                FROM portfolios WHERE user_id = ?
                """, Long.class, userId);
        long attendanceRewardCash = jdbc.queryForObject("""
                SELECT COALESCE(SUM(reward_cash), 0)
                FROM attendance_rewards
                WHERE user_id = ?
                """, Long.class, userId);
        // Like attendance, mission payouts are not trading performance; clients subtract both from P&L.
        long missionRewardCash = jdbc.queryForObject(
                "SELECT COALESCE(SUM(reward_cash), 0) FROM mission_rewards WHERE user_id = ?", Long.class, userId);
        // 대출 잔액은 빌린 돈이라 화면에서 순자산과 손익을 계산할 때 뺀다.
        long loanBalance = jdbc.queryForObject("SELECT loan_balance FROM users WHERE id = ?", Long.class, userId);
        long assetValue = positions.stream().mapToLong(Position::marketValue).sum();
        // ETF·선물·옵션 평가액도 계좌 총자산에 포함한다. 저장된 마지막 평가값을 쓴다.
        long derivativeAssetValue = AccountValuation.derivatives(jdbc,userId);
        long derivativeRealized = jdbc.queryForObject("""
                SELECT COALESCE(SUM(realized_pnl), 0) FROM derivative_positions WHERE user_id = ?
                """, Long.class, userId);
        return new Portfolio(cash, assetValue, cash + reservedCash + unsettledCash + assetValue + derivativeAssetValue,
                positions, unsettledCash, unsettledAssetValue, totalFees, realizedProfitLoss, attendanceRewardCash,
                missionRewardCash, loanBalance,market.adminRuntime()==null?0:market.adminRuntime().externalCash(userId),
                derivativeAssetValue, derivativeRealized);
    }

    private long currentPrice(String code) {
        return market.findStock(code).price();
    }

    record ExecutionSummary(long fee, String status, String settlementAt) { }
}
