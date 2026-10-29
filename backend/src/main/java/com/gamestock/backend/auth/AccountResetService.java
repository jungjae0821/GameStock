package com.gamestock.backend.auth;

import com.gamestock.backend.market.MarketBookCache;
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
    private final AuthService auth;

    public AccountResetService(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
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
            throw new IllegalArgumentException("인생 리셋은 계정당 한 번만 사용할 수 있습니다.");

        // Serialize with strategy claims so a pre-reset signal cannot spend the new starting cash.
        var strategies = jdbc.query("SELECT status FROM quant_projects WHERE user_id=? ORDER BY id FOR UPDATE",
                (rs, row) -> rs.getString(1), userId);
        if (strategies.stream().anyMatch(status -> status.equals("RUNNING") || status.equals("PAUSING")))
            throw new IllegalArgumentException("자동매매 주문 처리 중입니다. 전략을 중지하고 주문 결과를 확인한 뒤 리셋해 주세요.");
        jdbc.update("UPDATE quant_projects SET status='PAUSED',updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=?", userId);

        String resetNickname = auth.generateNickname();

        // Pending T+1 entries are matured first, so counterparties receive the
        // money/shares they are owed before this account is cleared.
        settleUserPendingPayments(userId);
        cancelOpenOrders(userId);
        jdbc.update("DELETE FROM portfolios WHERE user_id = ?", userId);
        // 열린 파생 포지션을 남기면 초기 자금과 별개로 평가액이 살아남는다.
        jdbc.update("DELETE FROM derivative_positions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM derivative_trades WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM attendance_rewards WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM mission_rewards WHERE user_id = ?", userId);
        jdbc.update("INSERT INTO admin_cash_epochs(user_id,last_adjustment_id) SELECT ?,COALESCE(MAX(id),0) FROM admin_cash_adjustments WHERE user_id=? ON DUPLICATE KEY UPDATE last_adjustment_id=VALUES(last_adjustment_id)",userId,userId);
        jdbc.update("DELETE FROM loan_ledger WHERE user_id = ?", userId);
        // 상세 화면의 개인 기능도 계정 리셋 범위에 포함한다.
        jdbc.update("DELETE FROM price_alerts WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM stock_comments WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM stock_tags WHERE created_by = ?", userId);
        jdbc.update("DELETE FROM user_watchlists WHERE user_id = ?", userId);
        // 리셋 전 잔고에 걸어 둔 자동 매도와 이전 자산 곡선은 새 출발과 이어지지 않는다.
        jdbc.update("DELETE FROM stop_orders WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM asset_snapshots WHERE user_id = ?", userId);
        // 리셋 닉네임을 바로 바꿀 수 있도록 이전 닉네임 변경 제한도 지운다.
        jdbc.update("""
                UPDATE users
                SET cash = ?,
                    loan_balance = 0,
                    loan_interest_on = NULL,
                    nickname = ?,
                    profile_image_url = '',
                    profile_completed = FALSE,
                    nickname_changed_at = NULL,
                    account_reset_at = CURRENT_TIMESTAMP(3),
                    reset_used_at = CURRENT_TIMESTAMP
                WHERE id = ? AND reset_used_at IS NULL
                """, STARTING_CASH, resetNickname, userId);
        Timestamp resetAt = jdbc.queryForObject("SELECT account_reset_at FROM users WHERE id = ?", Timestamp.class, userId);
        return new ResetResult("계정이 초기화되었습니다. 이제 처음처럼 다시 시작할 수 있습니다.",
                resetAt == null ? null : resetAt.toInstant().toString());
    }

    private void lockMarket() {
        jdbc.queryForObject("SELECT id FROM market_locks WHERE id = 1 FOR UPDATE", Integer.class);
        // The bot matching cache still holds this user's open orders and holdings.
        // Bumping the revisions in this transaction makes the next batch reload them.
        MarketBookCache.invalidate(jdbc, null);
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
