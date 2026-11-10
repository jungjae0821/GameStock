package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-time achievement titles and the weekly return-ranking titles.
 *
 * <p>One-time titles are checked when the client asks ({@link #check}); a grant is
 * a single INSERT IGNORE, so parallel checks award a title once. Weekly titles are
 * awarded by {@link #awardWeeklyTitles} at Korean Monday midnight: every week the
 * service stores each investor's starting asset, and the following Monday ranks the
 * week's return against it. During the week's last hour every tick refreshes each
 * investor's closing asset, so a late Monday run still ranks the week as it closed.
 * The weekly work lives in {@link WeeklyTitleAwards}, built here and sharing this
 * service's JdbcTemplate, TransactionTemplate, clock and investor-asset query.
 */
@Service
public class TitleService {
    private static final Logger log = LoggerFactory.getLogger(TitleService.class);
    static final long STARTING_CASH = 1_000_000L;
    static final ZoneId TITLE_ZONE = ZoneId.of("Asia/Seoul");
    /** Bottom-percentile cutoffs need the whole investor population; reuse it briefly. */
    private static final long POPULATION_CACHE_MILLIS = 60_000L;
    /** Real investors only, the same population as the public ranking. */
    static final String INVESTOR_FILTER = """
            u.password_hash NOT IN ('BOT', 'TRADER') AND u.username <> 'demo'
              AND COALESCE(u.role, 'USER') <> 'ADMIN'
            """;

    public record TitleDefinition(String id, String name, String description, String category, boolean weekly) { }
    public record UserTitle(String id, String name, String description, String category, boolean weekly,
                            boolean owned, boolean equipped, int count, String acquiredAt, String updatedAt) { }
    public record TitleStatus(List<UserTitle> titles, List<UserTitle> newlyAwarded, int ownedCount, int totalCount) { }
    public record WeeklyRankingEntry(int rank, String nickname, String profileImageUrl, long totalAsset,
                                     double weeklyReturnPercent, String equippedTitle, boolean equippedTitleWeekly) { }
    /** The running week's return ranking; {@code me} is the viewer's row when they take part. */
    public record WeeklyRanking(String weekStart, String weekEnd, String awardAt, int participants,
                                List<WeeklyRankingEntry> entries, WeeklyRankingEntry me) { }

    public static final List<TitleDefinition> TITLES = TitleCatalog.TITLES;
    private static final Map<String, TitleDefinition> BY_ID = TitleCatalog.BY_ID;

    private static final long[] ASSET_THRESHOLDS = {
            30_000_000L, 50_000_000L, 100_000_000L, 1_000_000_000L, 3_000_000_000L,
            5_000_000_000L, 10_000_000_000L, 100_000_000_000L};
    private static final String[] ASSET_TITLE_IDS = {
            "asset_30m", "asset_50m", "asset_100m", "asset_1b", "asset_3b", "asset_5b", "asset_10b", "asset_100b"};

    /** The catalog entry for a stored title id, or null for an unknown or missing id. */
    static TitleDefinition definition(String titleId) {
        return titleId == null ? null : BY_ID.get(titleId);
    }

    /** Name shown next to a nickname; weekly titles carry their win count ("n관왕"). */
    static String displayName(TitleDefinition title, int count) {
        return title.weekly() ? title.name() + " " + Math.max(1, count) + "관왕" : title.name();
    }

    private final JdbcTemplate jdbc;
    private AdminRuntimeControl adminRuntime;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void configureAdminRuntime(AdminRuntimeControl runtime){this.adminRuntime=runtime;}
    private final TransactionTemplate transactions;
    private Clock clock = Clock.systemUTC();
    private volatile Population population;
    private final WeeklyTitleAwards weekly;

    public TitleService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.weekly = new WeeklyTitleAwards(jdbc, transactions, () -> this.clock, this::investorAssets);
    }

    void useClock(Clock clock) { this.clock = clock; }

    void ensureTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_titles (
                  user_id BIGINT NOT NULL, title_id VARCHAR(40) NOT NULL,
                  win_count INT NOT NULL DEFAULT 1, notified BOOLEAN NOT NULL DEFAULT FALSE,
                  equipped BOOLEAN NOT NULL DEFAULT FALSE,
                  acquired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (user_id, title_id),
                  CONSTRAINT fk_user_title_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        Integer equippedColumn = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_titles' AND column_name = 'equipped'", Integer.class);
        if (equippedColumn != null && equippedColumn == 0)
            jdbc.execute("ALTER TABLE user_titles ADD COLUMN equipped BOOLEAN NOT NULL DEFAULT FALSE AFTER notified");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS weekly_return_weeks (
                  week_start DATE NOT NULL PRIMARY KEY,
                  snapshot_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  closing_at TIMESTAMP NULL,
                  awarded_at TIMESTAMP NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS weekly_return_snapshots (
                  week_start DATE NOT NULL, user_id BIGINT NOT NULL, baseline_asset BIGINT NOT NULL,
                  closing_asset BIGINT NULL,
                  PRIMARY KEY (week_start, user_id),
                  CONSTRAINT fk_weekly_snapshot_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        addColumnIfMissing("weekly_return_weeks", "closing_at", "TIMESTAMP NULL AFTER snapshot_at");
        addColumnIfMissing("weekly_return_snapshots", "closing_asset", "BIGINT NULL AFTER baseline_asset");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    /** Lists every title with the user's progress, without granting anything. */
    public TitleStatus status(long userId) {
        return status(userId, List.of());
    }

    /**
     * Grants every one-time title whose condition currently holds, then returns the
     * full list. Titles granted since the last check (including weekly awards) are
     * returned once in {@code newlyAwarded} so the client can announce them.
     */
    public TitleStatus check(long userId) {
        Set<String> owned = ownedIds(userId);
        Stats stats = stats(userId);
        if (stats != null) {
            grantIf(userId, owned, "newbie", true);
            grantIf(userId, owned, "ant", stats.maxQuantity >= 1);
            for (int index = 0; index < ASSET_TITLE_IDS.length; index++)
                grantIf(userId, owned, ASSET_TITLE_IDS[index], stats.totalAsset >= ASSET_THRESHOLDS[index]);
            grantIf(userId, owned, "loss_99", stats.returnPercent() <= -99.0);
            if (stats.investor && (!owned.contains("bottom_10") || !owned.contains("bottom_5"))) {
                Population current = population();
                grantIf(userId, owned, "bottom_10", current.inBottom(userId, 0.10));
                grantIf(userId, owned, "bottom_5", current.inBottom(userId, 0.05));
            }
            grantIf(userId, owned, "holder_50", stats.maxQuantity >= 50);
            grantIf(userId, owned, "holder_150", stats.maxQuantity >= 150);
            grantIf(userId, owned, "starter", stats.nicknameChanged);
            grantIf(userId, owned, "attend_7", stats.maxStreak >= 7);
            grantIf(userId, owned, "attend_30", stats.maxStreak >= 30);
            grantIf(userId, owned, "attend_180", stats.maxStreak >= 180);
            if (!owned.contains("trades_100")) {
                long maxDailyTrades = maxDailyTrades(userId);
                grantIf(userId, owned, "trades_10", maxDailyTrades >= 10);
                grantIf(userId, owned, "trades_100", maxDailyTrades >= 100);
            }
            // Holding two different stocks at once means one was bought while the other was held.
            grantIf(userId, owned, "diversify", stats.heldStocks >= 2);
            grantIf(userId, owned, "all_in", stats.totalAsset > 0 && stats.maxPositionValue * 10 >= stats.totalAsset * 9L);
            grantCollectorTitles(userId, owned);
        }
        List<String> fresh = jdbc.queryForList("SELECT title_id FROM user_titles WHERE user_id = ? AND notified = FALSE",
                String.class, userId);
        if (!fresh.isEmpty())
            jdbc.update("UPDATE user_titles SET notified = TRUE WHERE user_id = ? AND notified = FALSE", userId);
        return status(userId, fresh);
    }

    /**
     * Equips one owned title, or takes the current one off when {@code titleId} is
     * blank. A single UPDATE keeps at most one title equipped per user.
     */
    public TitleStatus equip(long userId, String titleId) {
        String id = titleId == null ? "" : titleId.trim();
        if (id.isEmpty()) {
            jdbc.update("UPDATE user_titles SET equipped = FALSE WHERE user_id = ? AND equipped = TRUE", userId);
            return status(userId);
        }
        if (!BY_ID.containsKey(id)) throw new IllegalArgumentException("존재하지 않는 칭호입니다.");
        if (!ownedIds(userId).contains(id)) throw new IllegalArgumentException("획득한 칭호만 장착할 수 있습니다.");
        jdbc.update("UPDATE user_titles SET equipped = (title_id = ?) WHERE user_id = ?", id, userId);
        return status(userId);
    }

    /** A redeemed code's result: the title list as in {@link TitleStatus} plus the cash it paid. */
    public record PromoResult(List<UserTitle> titles, List<UserTitle> newlyAwarded, int ownedCount, int totalCount,
                              long rewardCash) { }

    /**
     * Redeems a promotion code set up on the admin screen. Each account uses a code once.
     * The code row is locked so a limited code cannot be used past its limit. Cash goes in
     * as an admin cash adjustment, so ranking returns leave it out like other paid rewards.
     * Afterwards the regular check runs so collector titles that now hold are granted too.
     */
    public PromoResult redeem(long userId, String code) {
        String key = code == null ? "" : code.trim().toLowerCase(java.util.Locale.ROOT);
        long cash = transactions.execute(status -> {
            var rows = key.isEmpty() ? List.<Map<String, Object>>of()
                    : jdbc.queryForList("SELECT * FROM promo_codes WHERE code = ? FOR UPDATE", key);
            if (rows.isEmpty() || !Boolean.TRUE.equals(asBoolean(rows.get(0).get("active"))))
                throw new IllegalArgumentException("유효하지 않은 프로모션 코드입니다.");
            var promo = rows.get(0);
            Instant now = clock.instant();
            if (promo.get("starts_at") instanceof Timestamp starts && now.isBefore(starts.toInstant()))
                throw new IllegalArgumentException("아직 사용할 수 없는 프로모션 코드입니다.");
            if (promo.get("ends_at") instanceof Timestamp ends && !now.isBefore(ends.toInstant()))
                throw new IllegalArgumentException("사용 기간이 끝난 프로모션 코드입니다.");
            Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM promo_redemptions WHERE code = ? AND user_id = ?", Integer.class, key, userId);
            if (used != null && used > 0) throw new IllegalArgumentException("이미 사용한 프로모션 코드입니다.");
            long reward = ((Number) promo.get("reward_cash")).longValue();
            List<String> titleIds = splitTitles((String) promo.get("title_ids"));
            Set<String> owned = ownedIds(userId);
            // 사용 기록이 생기기 전에 칭호만 주던 코드를 이미 쓴 계정이다.
            if (reward == 0 && owned.containsAll(titleIds)) throw new IllegalArgumentException("이미 사용한 프로모션 코드입니다.");
            if (promo.get("max_uses") instanceof Number limit) {
                Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM promo_redemptions WHERE code = ?", Integer.class, key);
                if (total != null && total >= limit.intValue()) throw new IllegalArgumentException("준비된 수량이 모두 소진된 프로모션 코드입니다.");
            }
            jdbc.update("INSERT INTO promo_redemptions (code, user_id, reward_cash, title_ids) VALUES (?, ?, ?, ?)",
                    key, userId, reward, String.join(",", titleIds));
            for (String titleId : titleIds) grantIf(userId, owned, titleId, true);
            if (reward > 0) {
                jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reward, userId);
                Object admin = promo.get("created_by");
                jdbc.update("INSERT INTO admin_cash_adjustments (request_key, admin_id, user_id, amount, reason) VALUES (?, ?, ?, ?, ?)",
                        "promo:" + key + ":" + userId, admin == null ? 0L : ((Number) admin).longValue(), userId, reward, "프로모션 코드 " + key);
            }
            return reward;
        });
        TitleStatus status = check(userId);
        return new PromoResult(status.titles(), status.newlyAwarded(), status.ownedCount(), status.totalCount(), cash);
    }

    /** Stored comma list → known title ids, in catalog order is not required. */
    static List<String> splitTitles(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(BY_ID::containsKey).toList();
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean flag ? flag : value instanceof Number number ? number.intValue() != 0 : null;
    }

    private TitleStatus status(long userId, List<String> fresh) {
        Map<String, UserTitle> held = new HashMap<>();
        jdbc.query("SELECT title_id, win_count, acquired_at, updated_at, equipped FROM user_titles WHERE user_id = ?", rs -> {
            TitleDefinition title = BY_ID.get(rs.getString(1));
            if (title != null)
                held.put(title.id(), userTitle(title, true, rs.getBoolean(5), rs.getInt(2), rs.getTimestamp(3), rs.getTimestamp(4)));
        }, userId);
        List<UserTitle> titles = TITLES.stream()
                .map(title -> held.getOrDefault(title.id(), userTitle(title, false, false, 0, null, null)))
                .toList();
        List<UserTitle> newly = fresh.stream().map(held::get).filter(java.util.Objects::nonNull).toList();
        return new TitleStatus(titles, newly, held.size(), TITLES.size());
    }

    private UserTitle userTitle(TitleDefinition title, boolean owned, boolean equipped, int count,
                                Timestamp acquiredAt, Timestamp updatedAt) {
        return new UserTitle(title.id(), title.name(), title.description(), title.category(), title.weekly(),
                owned, equipped, count, iso(acquiredAt), iso(updatedAt));
    }

    private void grantCollectorTitles(long userId, Set<String> owned) {
        // Collector titles count themselves too, so repeat until nothing new is granted.
        boolean changed = true;
        while (changed) {
            int count = owned.size();
            changed = grantIf(userId, owned, "collector_5", count >= 5)
                    | grantIf(userId, owned, "collector_10", count >= 10)
                    | grantIf(userId, owned, "collector_15", count >= 15);
            boolean allOthers = TITLES.stream().filter(title -> !title.id().equals("collector_all"))
                    .allMatch(title -> owned.contains(title.id()));
            changed |= grantIf(userId, owned, "collector_all", allOthers);
        }
    }

    private boolean grantIf(long userId, Set<String> owned, String titleId, boolean condition) {
        if (!condition || owned.contains(titleId)) return false;
        jdbc.update("INSERT IGNORE INTO user_titles (user_id, title_id, acquired_at, updated_at) VALUES (?, ?, ?, ?)",
                userId, titleId, now(), now());
        owned.add(titleId);
        return true;
    }

    private Set<String> ownedIds(long userId) {
        return new LinkedHashSet<>(jdbc.queryForList("SELECT title_id FROM user_titles WHERE user_id = ?", String.class, userId));
    }

    private Stats stats(long userId) {
        List<Stats> rows = jdbc.query("""
                SELECT u.cash, u.loan_balance, u.nickname_changed_at IS NOT NULL AS nickname_changed,
                       (""" + INVESTOR_FILTER + """
                       ) AS investor,
                       COALESCE((SELECT SUM(o.reserved_cash) FROM orders o WHERE o.user_id = u.id AND o.status = 'OPEN'), 0) AS reserved_cash,
                       COALESCE((SELECT SUM(st.gross_amount - st.seller_fee) FROM settlements st WHERE st.seller_id = u.id AND st.status = 'PENDING'), 0) AS unsettled_cash,
                       COALESCE((SELECT SUM(ar.reward_cash) FROM attendance_rewards ar WHERE ar.user_id = u.id), 0)
                         + COALESCE((SELECT SUM(mr.reward_cash) FROM mission_rewards mr WHERE mr.user_id = u.id), 0) AS reward_cash,
                       COALESCE((SELECT MAX(ar.streak_day) FROM attendance_rewards ar WHERE ar.user_id = u.id), 0) AS max_streak
                FROM users u WHERE u.id = ?
                """, (rs, row) -> {
            Stats stats = new Stats();
            stats.cash = rs.getLong("cash");
            stats.loanBalance = rs.getLong("loan_balance");
            stats.nicknameChanged = rs.getBoolean("nickname_changed");
            stats.investor = rs.getBoolean("investor");
            stats.reservedCash = rs.getLong("reserved_cash");
            stats.unsettledCash = rs.getLong("unsettled_cash");
            stats.rewardCash = rs.getLong("reward_cash");
            stats.maxStreak = rs.getInt("max_streak");
            return stats;
        }, userId);
        if (rows.isEmpty()) return null;
        Stats stats = rows.get(0);
        if(adminRuntime!=null)stats.rewardCash+=adminRuntime.externalCash(userId);
        jdbc.query("""
                SELECT p.quantity, p.quantity * COALESCE(pm.mark_price, s.current_price) AS market_value
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                WHERE p.user_id = ? AND p.quantity > 0
                """, rs -> {
            int quantity = rs.getInt(1);
            long value = rs.getLong(2);
            stats.heldStocks++;
            stats.maxQuantity = Math.max(stats.maxQuantity, quantity);
            stats.maxPositionValue = Math.max(stats.maxPositionValue, value);
            stats.assetValue += value;
        }, userId);
        stats.totalAsset = stats.cash + stats.reservedCash + stats.unsettledCash + stats.assetValue + AccountValuation.derivatives(jdbc,userId) - stats.loanBalance;
        return stats;
    }

    /** The most fills this user had on any single Korean calendar day. */
    private long maxDailyTrades(long userId) {
        Long count = jdbc.query("""
                SELECT COUNT(*) FROM (
                  SELECT id, created_at FROM trades WHERE buyer_id = ?
                  UNION
                  SELECT id, created_at FROM trades WHERE seller_id = ?
                ) t
                GROUP BY DATE(CONVERT_TZ(t.created_at, '+00:00', '+09:00'))
                ORDER BY COUNT(*) DESC LIMIT 1
                """, (rs, row) -> rs.getLong(1), userId, userId).stream().findFirst().orElse(0L);
        return count == null ? 0 : count;
    }

    private Population population() {
        Population current = population;
        long nowMillis = clock.millis();
        if (current != null && nowMillis - current.loadedAt < POPULATION_CACHE_MILLIS) return current;
        List<long[]> returns = new ArrayList<>();
        for (InvestorAsset investor : investorAssets()) {
            // Return in basis points so ties compare exactly.
            long basisPoints = Math.round((investor.adjustedAsset - STARTING_CASH) * 10_000.0 / STARTING_CASH);
            returns.add(new long[]{investor.userId, basisPoints});
        }
        // Worst return first; on a tie the newer account counts as lower.
        returns.sort(Comparator.<long[]>comparingLong(entry -> entry[1]).thenComparingLong(entry -> -entry[0]));
        Map<Long, Integer> positionFromBottom = new HashMap<>();
        for (int index = 0; index < returns.size(); index++) positionFromBottom.put(returns.get(index)[0], index + 1);
        current = new Population(positionFromBottom, returns.size(), nowMillis);
        population = current;
        return current;
    }

    /** Asset excluding attendance and mission payouts and loans, the basis of every return figure. */
    private List<InvestorAsset> investorAssets() {
        Map<Long,Long> externalCash=adminRuntime==null?Map.of():adminRuntime.externalCash();
        return jdbc.query("""
                SELECT u.id, u.account_reset_at,
                       """ + AccountValuation.GROSS_ASSET + """
                       - COALESCE((SELECT SUM(ar.reward_cash) FROM attendance_rewards ar WHERE ar.user_id = u.id), 0)
                       - COALESCE((SELECT SUM(mr.reward_cash) FROM mission_rewards mr WHERE mr.user_id = u.id), 0)
                       - u.loan_balance AS adjusted_asset
                FROM users u
                WHERE """ + " " + INVESTOR_FILTER, (rs, row) -> new InvestorAsset(rs.getLong("id"),
                rs.getLong("adjusted_asset")-externalCash.getOrDefault(rs.getLong("id"),0L), rs.getTimestamp("account_reset_at")));
    }

    /**
     * Runs every minute. On the first run of a Korean week it ranks the previous
     * week's returns, awards the weekly titles and records this week's baselines.
     * The claim columns make the work happen once even with several replicas.
     */
    @Scheduled(fixedDelayString = "${gamestock.titles.weekly-check-ms:60000}", initialDelayString = "${gamestock.titles.weekly-initial-delay-ms:60000}")
    public void weeklyTick() {
        try {
            awardWeeklyTitles();
        } catch (RuntimeException error) {
            log.warn("주간 칭호 처리에 실패했습니다. 다음 주기에 다시 시도합니다.", error);
        }
    }

    void awardWeeklyTitles() {
        weekly.awardWeeklyTitles();
    }

    /**
     * The running Korean week's return ranking, ranked the way Monday's award will rank
     * it. The computation and its short cache live in {@link WeeklyTitleAwards}.
     */
    public WeeklyRanking weeklyRanking(Long viewerId) {
        return weekly.weeklyRanking(viewerId);
    }

    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private static String iso(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant().toString(); }

    private static final class Stats {
        long cash, loanBalance, reservedCash, unsettledCash, assetValue, totalAsset, rewardCash, maxPositionValue;
        int maxQuantity, heldStocks, maxStreak;
        boolean nicknameChanged, investor;

        double returnPercent() { return (totalAsset - STARTING_CASH - rewardCash) * 100.0 / STARTING_CASH; }
    }

    record InvestorAsset(long userId, long adjustedAsset, Timestamp resetAt) { }

    private record Population(Map<Long, Integer> positionFromBottom, int size, long loadedAt) {
        /** True when the user is within the worst {@code share} of investors (at least one full slot). */
        boolean inBottom(long userId, double share) {
            Integer position = positionFromBottom.get(userId);
            int slots = (int) Math.floor(size * share);
            return position != null && slots >= 1 && position <= slots;
        }
    }
}
