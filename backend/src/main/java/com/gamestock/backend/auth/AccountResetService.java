package com.gamestock.backend.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/**
 * One-time player reset. Historical fills stay in the market ledger so other
 * investors' positions remain valid; the reset user's private views are
 * separated by account_reset_at.
 */
@Service
public class AccountResetService {
    private static final long STARTING_CASH = 1_000_000L;

    private final JdbcTemplate jdbc;

    public AccountResetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ResetResult reset(long userId) {
        lockMarket();
        ResetState state = jdbc.query("""
                SELECT reset_used_at FROM users WHERE id = ? FOR UPDATE
                """, (rs, row) -> new ResetState(rs.getTimestamp("reset_used_at")), userId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("계정을 찾을 수 없습니다."));
        if (state.resetUsedAt() != null)
            throw new IllegalArgumentException("인생 리셋은 Google 계정당 한 번만 사용할 수 있습니다.");

        // Pending T+1 entries are matured first, so counterparties receive the
        // money/shares they are owed before this account is cleared.
        settleUserPendingPayments(userId);
        cancelOpenOrders(userId);
        jdbc.update("DELETE FROM portfolios WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM attendance_rewards WHERE user_id = ?", userId);
        jdbc.update("""
                UPDATE users
                SET cash = ?,
                    nickname = LEFT(CONCAT('reset_', id, '_', UNIX_TIMESTAMP()), 50),
                    profile_image_url = '',
                    profile_completed = FALSE,
                    account_reset_at = CURRENT_TIMESTAMP,
                    reset_used_at = CURRENT_TIMESTAMP
                WHERE id = ? AND reset_used_at IS NULL
                """, STARTING_CASH, userId);
        Timestamp resetAt = jdbc.queryForObject("SELECT account_reset_at FROM users WHERE id = ?", Timestamp.class, userId);
        return new ResetResult("계정이 초기화되었습니다. 이제 처음처럼 다시 시작할 수 있습니다.",
                resetAt == null ? null : resetAt.toInstant().toString());
    }

    private void lockMarket() {
        jdbc.queryForObject("SELECT id FROM market_locks WHERE id = 1 FOR UPDATE", Integer.class);
    }

    private void cancelOpenOrders(long userId) {
        var openOrders = jdbc.query("""
                SELECT id, COALESCE(reserved_cash, 0) AS reserved_cash
                FROM orders WHERE user_id = ? AND status = 'OPEN' FOR UPDATE
                """, (rs, row) -> new OpenOrder(rs.getLong("id"), rs.getLong("reserved_cash")), userId);
        for (OpenOrder order : openOrders) {
            if (order.reservedCash() > 0)
                jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), userId);
        }
        jdbc.update("""
                UPDATE orders
                SET status = 'CANCELLED', remaining_quantity = 0,
                    reserved_cash = 0, reserved_quantity = 0
                WHERE user_id = ? AND status = 'OPEN'
                """, userId);
    }

    private void settleUserPendingPayments(long userId) {
        var pending = jdbc.query("""
                SELECT id, buyer_id, seller_id, stock_id, quantity, gross_amount, seller_fee
                FROM settlements
                WHERE status = 'PENDING' AND (buyer_id = ? OR seller_id = ?)
                ORDER BY settlement_at ASC, id ASC
                FOR UPDATE
                """, (rs, row) -> new PendingSettlement(
                rs.getLong("id"), rs.getLong("buyer_id"), rs.getLong("seller_id"),
                rs.getLong("stock_id"), rs.getInt("quantity"), rs.getLong("gross_amount"),
                rs.getLong("seller_fee")), userId, userId);
        for (PendingSettlement settlement : pending) {
            jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?",
                    settlement.grossAmount() - settlement.sellerFee(), settlement.sellerId());
            jdbc.update("""
                    UPDATE portfolios
                    SET settled_quantity = LEAST(quantity, COALESCE(settled_quantity, quantity) + ?)
                    WHERE user_id = ? AND stock_id = ?
                    """, settlement.quantity(), settlement.buyerId(), settlement.stockId());
            jdbc.update("UPDATE settlements SET status = 'SETTLED', settled_at = CURRENT_TIMESTAMP WHERE id = ?",
                    settlement.id());
        }
    }

    public record ResetResult(String message, String resetAt) { }

    private record ResetState(Timestamp resetUsedAt) { }
    private record OpenOrder(long id, long reservedCash) { }
    private record PendingSettlement(long id, long buyerId, long sellerId, long stockId,
                                     int quantity, long grossAmount, long sellerFee) { }
}
