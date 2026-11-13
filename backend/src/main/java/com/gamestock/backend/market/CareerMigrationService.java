package com.gamestock.backend.market;

import com.gamestock.backend.auth.AccountResetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one-off move of existing members onto the prop desk career: everyone restarts as
 * a trainee with a fresh 1,000,000 won trading account, 10% of their old net asset (at
 * most 10,000,000 won) goes to the personal wallet as severance, and their shares move
 * to the liquidity provider at no cost so the float stays the same.
 *
 * <p>{@link #preview()} reads only and reports what a run would do. {@link #run} needs the
 * market and bots paused, like the admin season reset, and migrates each member in their
 * own transaction; a member already migrated is skipped, so a failed run can be resumed.
 */
@Service
public class CareerMigrationService {
    private static final Logger log = LoggerFactory.getLogger(CareerMigrationService.class);
    static final double SEVERANCE_RATE = 0.10;
    static final long SEVERANCE_CAP = 10_000_000L;
    static final int MAX_USERS = 10_000;
    static final String FOUNDER_TITLE = "founder";
    private static final String TARGETS = """
            SELECT c.user_id FROM career_state c JOIN users u ON u.id = c.user_id
            WHERE c.legacy = TRUE AND c.migrated_at IS NULL AND u.password_hash NOT IN ('BOT', 'TRADER')
              AND u.username <> 'demo' AND COALESCE(u.role, 'USER') <> 'ADMIN'
            ORDER BY c.user_id
            """;

    public record Member(long userId, String nickname, long netAsset, long severance, long loanBalance, long refundedTapStake,
                         Map<String, Long> shares) { }
    public record StockMove(String code, long fromMembers, long lpBefore, long lpAfter, long maxInventory, boolean overMax) { }
    public record Report(boolean dryRun, int members, long totalNetAsset, long totalSeverance, long maxSeverance,
                         int cappedMembers, Map<String, Integer> severanceBands, List<StockMove> stocks, List<Member> largest) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountResetService resets;
    private final AdminSupport support;
    private final AdminRuntimeControl runtime;

    public CareerMigrationService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, AccountResetService resets,
                                  AdminSupport support, AdminRuntimeControl runtime) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.resets = resets;
        this.support = support;
        this.runtime = runtime;
    }

    /** What a run would do now, without changing anything. */
    public Report preview() {
        List<Long> targets = targets();
        List<Member> members = new ArrayList<>();
        for (long userId : targets) members.add(member(userId, openTapStake(userId)));
        return report(true, members);
    }

    public Report run(long actor, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("이전 사유를 적어 주세요.");
        var settings = runtime.settings();
        if (!settings.marketPaused() || !settings.botsPaused())
            throw new IllegalArgumentException("시장과 봇을 모두 정지한 뒤 이전해 주세요.");
        List<Long> targets = targets();
        List<Member> done = new ArrayList<>();
        for (long userId : targets) {
            Member member = transactions.execute(status -> migrate(actor, reason, userId));
            if (member != null) done.add(member);
        }
        support.audit(actor, "CAREER_MIGRATION", "all", reason, Map.of("targets", targets.size()), Map.of("migrated", done.size()));
        log.info("커리어 이전 완료: {}명", done.size());
        return report(false, done);
    }

    private List<Long> targets() {
        List<Long> targets = jdbc.queryForList(TARGETS, Long.class);
        if (targets.size() > MAX_USERS) throw new IllegalArgumentException("이전은 한 번에 최대 " + MAX_USERS + "명입니다.");
        return targets;
    }

    private Member migrate(long actor, String reason, long userId) {
        AdminSupport.lock(jdbc);
        // The bot matching cache holds this member's orders and holdings; make the next batch reload them.
        MarketBookCache.invalidate(jdbc, null);
        var state = jdbc.queryForList("SELECT legacy, migrated_at FROM career_state WHERE user_id = ? FOR UPDATE", userId);
        if (state.isEmpty() || state.get(0).get("migrated_at") != null) return null;
        jdbc.update("UPDATE quant_projects SET status = 'PAUSED', updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?", userId);
        resets.settleUserPendingPayments(userId);
        resets.cancelOpenOrders(userId);
        jdbc.update("DELETE FROM stop_orders WHERE user_id = ?", userId);
        // Bets placed from trading cash before the wallet existed are refunded, not lost: the member did nothing wrong.
        long refunded = openTapStake(userId);
        if (refunded > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", refunded, userId);
        jdbc.update("DELETE FROM tap_bets WHERE user_id = ? AND status = 'OPEN' AND wallet = FALSE", userId);

        Member counted = member(userId, 0);
        Member member = new Member(counted.userId(), counted.nickname(), counted.netAsset(), counted.severance(),
                counted.loanBalance(), refunded, counted.shares());
        var before = jdbc.queryForMap("SELECT cash, loan_balance FROM users WHERE id = ?", userId);
        support.audit(actor, "CAREER_MIGRATION_USER", userId, reason,
                Map.of("cash", before.get("cash"), "loanBalance", before.get("loan_balance"), "netAsset", member.netAsset(),
                        "shares", member.shares()),
                Map.of("cash", CareerGrade.TRAINEE.allocation, "severance", member.severance()));

        jdbc.update("""
                UPDATE derivative_positions SET status = 'RESET', quantity = 0, margin = 0, mark_value = 0,
                  closed_at = CURRENT_TIMESTAMP(3), close_reason = 'CAREER_MIGRATION'
                WHERE user_id = ? AND status = 'OPEN'
                """, userId);
        moveSharesToProvider(userId);
        jdbc.update("DELETE FROM portfolios WHERE user_id = ?", userId);
        // Old rewards were trading cash that this reset clears, so returns must stop subtracting them.
        jdbc.update("UPDATE attendance_rewards SET wallet_cash = wallet_cash + reward_cash, reward_cash = 0 WHERE user_id = ?", userId);
        jdbc.update("UPDATE mission_rewards SET wallet_cash = wallet_cash + reward_cash, reward_cash = 0 WHERE user_id = ?", userId);
        jdbc.update("""
                INSERT INTO admin_cash_epochs (user_id, last_adjustment_id)
                SELECT ?, COALESCE(MAX(id), 0) FROM admin_cash_adjustments WHERE user_id = ?
                ON DUPLICATE KEY UPDATE last_adjustment_id = VALUES(last_adjustment_id)
                """, userId, userId);
        jdbc.update("DELETE FROM asset_snapshots WHERE user_id = ?", userId);
        jdbc.update("""
                UPDATE users SET cash = ?, loan_balance = 0, loan_interest_on = NULL, account_reset_at = CURRENT_TIMESTAMP(3)
                WHERE id = ?
                """, CareerGrade.TRAINEE.allocation, userId);
        long fresh = CareerGrade.TRAINEE.allocation;
        jdbc.update("""
                UPDATE weekly_return_snapshots SET baseline_asset = ?, closing_asset = NULL
                WHERE user_id = ? AND week_start = (SELECT w.week_start FROM (SELECT MAX(week_start) AS week_start FROM weekly_return_weeks) w)
                """, fresh, userId);
        jdbc.update("""
                UPDATE admin_season_entries e JOIN admin_seasons s ON s.id = e.season_id
                SET e.opening_asset = ? WHERE e.user_id = ? AND s.status = 'ACTIVE'
                """, fresh, userId);

        if (member.severance() > 0)
            PersonalWallet.credit(jdbc, userId, "severance:" + userId, PersonalWallet.SEVERANCE, member.severance(), "전 직장 퇴직금");
        jdbc.update("""
                UPDATE career_state SET grade = 'TRAINEE', grade_since = CURRENT_TIMESTAMP(3), grade_base = ?, outside_cash = 0, return_streak = 0, high_water_mark = ?,
                  ojt_steps = '', day_open_on = NULL, day_open_asset = NULL, day_locked_until = NULL, lock_reason = NULL,
                  breach_count = 0, last_settled_on = NULL, migrated_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                WHERE user_id = ?
                """, fresh, fresh, userId);
        jdbc.update("INSERT INTO career_events (user_id, kind, from_grade, to_grade, asset_at, top_up) VALUES (?, 'MIGRATE', NULL, 'TRAINEE', ?, 0)",
                userId, member.netAsset());
        jdbc.update("""
                INSERT IGNORE INTO user_titles (user_id, title_id, win_count, notified, acquired_at, updated_at)
                VALUES (?, ?, 1, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId, FOUNDER_TITLE);
        return member;
    }

    /**
     * The member's shares go to the liquidity provider without payment: the trading account
     * is reset anyway, and the shares' value already sits in the net asset the severance
     * comes from. The provider's average price takes the shares in at the mark price.
     */
    private void moveSharesToProvider(long userId) {
        long provider = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, LiquidityProviderAccounts.USERNAME);
        var holdings = jdbc.queryForList("""
                SELECT p.stock_id, p.quantity, COALESCE(pm.mark_price, s.current_price) AS mark
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                WHERE p.user_id = ? AND p.quantity > 0 FOR UPDATE
                """, userId);
        for (var holding : holdings) {
            long stock = ((Number) holding.get("stock_id")).longValue();
            long quantity = ((Number) holding.get("quantity")).longValue();
            long mark = ((Number) holding.get("mark")).longValue();
            var rows = jdbc.queryForList("SELECT quantity, average_price FROM portfolios WHERE user_id = ? AND stock_id = ? FOR UPDATE",
                    provider, stock);
            if (rows.isEmpty()) {
                jdbc.update("""
                        INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                        VALUES (?, ?, ?, ?, ?, 0)
                        """, provider, stock, quantity, quantity, mark);
                continue;
            }
            long held = ((Number) rows.get(0).get("quantity")).longValue();
            long average = ((Number) rows.get(0).get("average_price")).longValue();
            long next = held + quantity <= 0 ? mark : Math.round((held * (double) average + quantity * (double) mark) / (held + quantity));
            jdbc.update("""
                    UPDATE portfolios SET quantity = quantity + ?, settled_quantity = settled_quantity + ?, average_price = ?
                    WHERE user_id = ? AND stock_id = ?
                    """, quantity, quantity, next, provider, stock);
        }
    }

    private Member member(long userId, long tapRefund) {
        // Severance uses the confirmed asset (each holding at the lower of cost and mark), so pumping a
        // thin stock's mark just before the migration does not raise it.
        var row = jdbc.queryForMap("SELECT u.nickname, u.loan_balance, " + CareerService.CONFIRMED_ASSET + " AS net FROM users u WHERE u.id = ?", userId);
        long net = ((Number) row.get("net")).longValue() + tapRefund;
        long severance = Math.max(0, Math.min(SEVERANCE_CAP, (long) Math.floor(net * SEVERANCE_RATE)));
        Map<String, Long> shares = new TreeMap<>();
        jdbc.query("""
                SELECT s.stock_code, p.quantity FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                WHERE p.user_id = ? AND p.quantity > 0
                """, rs -> { shares.put(rs.getString(1), rs.getLong(2)); }, userId);
        return new Member(userId, (String) row.get("nickname"), net, severance, ((Number) row.get("loan_balance")).longValue(),
                tapRefund, shares);
    }

    private long openTapStake(long userId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(stake), 0) FROM tap_bets WHERE user_id = ? AND status = 'OPEN' AND wallet = FALSE",
                Long.class, userId);
        return value == null ? 0 : value;
    }

    private Report report(boolean dryRun, List<Member> members) {
        long totalNet = 0, totalSeverance = 0, maxSeverance = 0;
        int capped = 0;
        Map<String, Integer> bands = new LinkedHashMap<>();
        for (String band : List.of("0", "~10만", "~100만", "~500만", "1,000만(상한)")) bands.put(band, 0);
        Map<String, Long> moved = new TreeMap<>();
        for (Member member : members) {
            totalNet += member.netAsset();
            totalSeverance += member.severance();
            maxSeverance = Math.max(maxSeverance, member.severance());
            if (member.severance() >= SEVERANCE_CAP) capped++;
            String band = member.severance() == 0 ? "0" : member.severance() <= 100_000 ? "~10만"
                    : member.severance() <= 1_000_000 ? "~100만" : member.severance() < SEVERANCE_CAP ? "~500만" : "1,000만(상한)";
            bands.merge(band, 1, Integer::sum);
            member.shares().forEach((code, quantity) -> moved.merge(code, quantity, Long::sum));
        }
        List<StockMove> stocks = new ArrayList<>();
        Map<String, long[]> books = new HashMap<>();
        jdbc.query("""
                SELECT s.stock_code, COALESCE(p.quantity, 0), COALESCE(r.max_inventory, 0) FROM stocks s
                LEFT JOIN portfolios p ON p.stock_id = s.id AND p.user_id = (SELECT id FROM users WHERE username = ?)
                LEFT JOIN lp_risk_books r ON r.stock_id = s.id
                """, rs -> { books.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)}); }, LiquidityProviderAccounts.USERNAME);
        moved.forEach((code, quantity) -> {
            long[] book = books.getOrDefault(code, new long[]{0, 0});
            // After a run the provider already holds the moved shares.
            long lpBefore = dryRun ? book[0] : book[0] - quantity;
            long lpAfter = lpBefore + quantity;
            stocks.add(new StockMove(code, quantity, lpBefore, lpAfter, book[1], book[1] > 0 && lpAfter > book[1]));
        });
        List<Member> largest = members.stream().sorted((a, b) -> Long.compare(b.netAsset(), a.netAsset())).limit(20).toList();
        return new Report(dryRun, members.size(), totalNet, totalSeverance, maxSeverance, capped, bands, stocks, largest);
    }
}
