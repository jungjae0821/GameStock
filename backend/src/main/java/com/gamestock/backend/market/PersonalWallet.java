package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;

/**
 * The member's own money, apart from the firm's trading account ({@code users.cash}).
 *
 * <p>Bonuses and every non-trading reward land here; tap betting, the billboard and
 * homes are paid from here. Money never moves from here into the trading account.
 * Every change is one {@code personal_ledger} row whose {@code request_key} makes a
 * repeated payment a no-op.
 *
 * <p>Inside a transaction the ledger's {@code balance_after} is exact; outside one the
 * balance itself is still right, only {@code balance_after} may race.
 */
public final class PersonalWallet {
    public static final String BONUS = "BONUS";
    public static final String ATTENDANCE = "ATTENDANCE";
    public static final String MISSION = "MISSION";
    public static final String PREDICTION = "PREDICTION";
    public static final String SEASON = "SEASON";
    public static final String PROMO = "PROMO";
    public static final String SEVERANCE = "SEVERANCE";
    public static final String BILLBOARD = "BILLBOARD";
    public static final String TAP = "TAP";

    public record Entry(long id, String kind, long amount, long balanceAfter, String memo, String at) { }

    private PersonalWallet() { }

    /**
     * Creates the member's career row on first use; a new account starts as a trainee.
     * The grade starts at sign-up so trades made before the row existed still count for OJT.
     */
    public static void ensureRow(JdbcTemplate jdbc, long userId) {
        // A plain read takes no lock. INSERT IGNORE on an existing row takes a shared lock that a
        // later FOR UPDATE in the same transaction must upgrade, which deadlocks two such transactions.
        if (!jdbc.queryForList("SELECT 1 FROM career_state WHERE user_id = ?", Integer.class, userId).isEmpty()) return;
        jdbc.update("""
                INSERT IGNORE INTO career_state (user_id, grade_since, grade_base, high_water_mark)
                SELECT id, created_at, ?, ? FROM users WHERE id = ?
                """, CareerGrade.TRAINEE.allocation, CareerGrade.TRAINEE.allocation, userId);
    }

    public static long balance(JdbcTemplate jdbc, long userId) {
        List<Long> rows = jdbc.queryForList("SELECT personal_cash FROM career_state WHERE user_id = ?", Long.class, userId);
        return rows.isEmpty() || rows.get(0) == null ? 0 : rows.get(0);
    }

    /** Pays {@code amount} in once per {@code key}; returns false when that key was already paid. */
    public static boolean credit(JdbcTemplate jdbc, long userId, String key, String kind, long amount, String memo) {
        if (amount < 0) throw new IllegalArgumentException("지급액은 0 이상이어야 합니다.");
        return apply(jdbc, userId, key, kind, amount, memo);
    }

    /** Takes {@code amount} out once per {@code key}; fails when the balance is short. */
    public static boolean debit(JdbcTemplate jdbc, long userId, String key, String kind, long amount, String memo) {
        if (amount <= 0) throw new IllegalArgumentException("결제액은 0보다 커야 합니다.");
        return apply(jdbc, userId, key, kind, -amount, memo);
    }

    public static List<Entry> recent(JdbcTemplate jdbc, long userId, int limit) {
        return jdbc.query("""
                SELECT id, kind, amount, balance_after, memo, created_at FROM personal_ledger
                WHERE user_id = ? ORDER BY id DESC LIMIT ?
                """, (rs, row) -> new Entry(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                rs.getTimestamp(6).toInstant().toString()), userId, Math.max(1, Math.min(limit, 100)));
    }

    /**
     * The key row goes in first so a repeat stops before money moves; the balance then
     * changes in one guarded UPDATE, so it stays right even outside a transaction.
     */
    private static boolean apply(JdbcTemplate jdbc, long userId, String key, String kind, long delta, String memo) {
        ensureRow(jdbc, userId);
        String text = memo == null ? "" : memo.length() > 120 ? memo.substring(0, 120) : memo;
        int inserted = jdbc.update("""
                INSERT IGNORE INTO personal_ledger (request_key, user_id, kind, amount, balance_after, memo)
                VALUES (?, ?, ?, ?, 0, ?)
                """, key, userId, kind, delta, text);
        if (inserted == 0) return false;
        int moved = jdbc.update("""
                UPDATE career_state SET personal_cash = personal_cash + ?, updated_at = CURRENT_TIMESTAMP(3)
                WHERE user_id = ? AND personal_cash + ? >= 0
                """, delta, userId, delta);
        if (moved == 0) {
            jdbc.update("DELETE FROM personal_ledger WHERE request_key = ?", key);
            throw new IllegalArgumentException("내 지갑 잔액이 부족해요. " + String.format(Locale.ROOT, "%,d", -delta) + "원이 필요해요.");
        }
        jdbc.update("UPDATE personal_ledger SET balance_after = (SELECT personal_cash FROM career_state WHERE user_id = ?) WHERE request_key = ?",
                userId, key);
        return true;
    }
}
