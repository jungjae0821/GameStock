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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
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
 * week's return against it.
 */
@Service
public class TitleService {
    private static final Logger log = LoggerFactory.getLogger(TitleService.class);
    private static final long STARTING_CASH = 1_000_000L;
    private static final ZoneId TITLE_ZONE = ZoneId.of("Asia/Seoul");
    /** Bottom-percentile cutoffs need the whole investor population; reuse it briefly. */
    private static final long POPULATION_CACHE_MILLIS = 60_000L;
    /** Real investors only, the same population as the public ranking. */
    private static final String INVESTOR_FILTER = """
            u.password_hash NOT IN ('BOT', 'TRADER') AND u.username <> 'demo'
              AND COALESCE(u.role, 'USER') <> 'ADMIN'
            """;

    public record TitleDefinition(String id, String name, String description, String category, boolean weekly) { }
    public record UserTitle(String id, String name, String description, String category, boolean weekly,
                            boolean owned, boolean equipped, int count, String acquiredAt, String updatedAt) { }
    public record TitleStatus(List<UserTitle> titles, List<UserTitle> newlyAwarded, int ownedCount, int totalCount) { }

    public static final List<TitleDefinition> TITLES = List.of(
            new TitleDefinition("newbie", "뉴비", "계정으로 처음 로그인", "자산", false),
            new TitleDefinition("ant", "개미", "아무 주식이나 1주 보유", "자산", false),
            new TitleDefinition("asset_30m", "소액 자산가", "총 자산 3천만원 돌파", "자산", false),
            new TitleDefinition("asset_50m", "중산층 자산가", "총 자산 5천만원 돌파", "자산", false),
            new TitleDefinition("asset_100m", "1억원의 사나이", "총 자산 1억원 돌파", "자산", false),
            new TitleDefinition("asset_1b", "10억원의 사나이", "총 자산 10억원 돌파", "자산", false),
            new TitleDefinition("asset_3b", "30억원의 사나이", "총 자산 30억원 돌파", "자산", false),
            new TitleDefinition("asset_5b", "50억원의 사나이", "총 자산 50억원 돌파", "자산", false),
            new TitleDefinition("asset_10b", "100억의 사나이", "총 자산 100억원 돌파", "자산", false),
            new TitleDefinition("asset_100b", "세계1위 부자", "총 자산 1000억원 돌파", "자산", false),
            new TitleDefinition("bottom_10", "흑우", "수익률 하위 10% 달성", "수익률", false),
            new TitleDefinition("bottom_5", "호구", "수익률 하위 5% 달성", "수익률", false),
            new TitleDefinition("loss_99", "주식못함", "수익률 -99% 달성", "수익률", false),
            new TitleDefinition("holder_50", "큰손", "한 종목 50주 이상 보유", "보유", false),
            new TitleDefinition("holder_150", "대주주", "한 종목 150주 이상 보유", "보유", false),
            new TitleDefinition("starter", "스타터", "닉네임 변경", "활동", false),
            new TitleDefinition("attend_7", "일주일 개근", "7일 연속 매일 로그인", "활동", false),
            new TitleDefinition("attend_30", "한달 개근", "30일 연속 매일 로그인", "활동", false),
            new TitleDefinition("attend_180", "개근상", "180일(6달) 연속 매일 로그인", "활동", false),
            new TitleDefinition("trades_10", "투자 준비생", "하루 체결 10회 이상", "거래", false),
            new TitleDefinition("trades_100", "단타충", "하루 체결 100회 이상", "거래", false),
            new TitleDefinition("diversify", "존버의 신", "한 종목을 1주 이상 보유한 채로 다른 종목 1주 이상 매수", "거래", false),
            new TitleDefinition("all_in", "인생은 한방", "단일 종목에 전 재산의 90% 이상 투자", "거래", false),
            new TitleDefinition("collector_5", "칭호 수집가", "칭호 5종류 이상 보유", "수집", false),
            new TitleDefinition("collector_10", "칭호 컬렉터", "칭호 10종류 이상 보유", "수집", false),
            new TitleDefinition("collector_15", "칭호 컬렉팅 마스터", "칭호 15종류 이상 보유", "수집", false),
            new TitleDefinition("collector_all", "칭호 올컬렉터", "다른 모든 칭호 보유", "수집", false),
            new TitleDefinition("weekly_3", "주간 수익률 3위", "주간 수익률 3위 이내 · 매주 월요일 지급", "주간", true),
            new TitleDefinition("weekly_2", "주간 수익률 2위", "주간 수익률 2위 이내 · 매주 월요일 지급", "주간", true),
            new TitleDefinition("weekly_1", "주간 수익률 1위", "주간 수익률 1위 · 매주 월요일 지급", "주간", true));
    private static final Map<String, TitleDefinition> BY_ID = new HashMap<>();
    static { for (TitleDefinition title : TITLES) BY_ID.put(title.id(), title); }

    private static final long[] ASSET_THRESHOLDS = {
            30_000_000L, 50_000_000L, 100_000_000L, 1_000_000_000L, 3_000_000_000L,
            5_000_000_000L, 10_000_000_000L, 100_000_000_000L};
    private static final String[] ASSET_TITLE_IDS = {
            "asset_30m", "asset_50m", "asset_100m", "asset_1b", "asset_3b", "asset_5b", "asset_10b", "asset_100b"};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private Clock clock = Clock.systemUTC();
    private volatile Population population;

    public TitleService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
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
                  awarded_at TIMESTAMP NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS weekly_return_snapshots (
                  week_start DATE NOT NULL, user_id BIGINT NOT NULL, baseline_asset BIGINT NOT NULL,
                  PRIMARY KEY (week_start, user_id),
                  CONSTRAINT fk_weekly_snapshot_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
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
                SELECT u.cash, u.nickname_changed_at IS NOT NULL AS nickname_changed,
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
        stats.totalAsset = stats.cash + stats.reservedCash + stats.unsettledCash + stats.assetValue;
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

    /** Asset excluding attendance and mission payouts, the basis of every return figure. */
    private List<InvestorAsset> investorAssets() {
        return jdbc.query("""
                SELECT u.id, u.account_reset_at,
                       u.cash
                       + COALESCE((SELECT SUM(o.reserved_cash) FROM orders o WHERE o.user_id = u.id AND o.status = 'OPEN'), 0)
                       + COALESCE((SELECT SUM(st.gross_amount - st.seller_fee) FROM settlements st WHERE st.seller_id = u.id AND st.status = 'PENDING'), 0)
                       + COALESCE((SELECT SUM(p.quantity * COALESCE(pm.mark_price, s.current_price))
                                   FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                                   LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                                   WHERE p.user_id = u.id AND p.quantity > 0), 0)
                       - COALESCE((SELECT SUM(ar.reward_cash) FROM attendance_rewards ar WHERE ar.user_id = u.id), 0)
                       - COALESCE((SELECT SUM(mr.reward_cash) FROM mission_rewards mr WHERE mr.user_id = u.id), 0) AS adjusted_asset
                FROM users u
                WHERE """ + " " + INVESTOR_FILTER, (rs, row) -> new InvestorAsset(rs.getLong("id"),
                rs.getLong("adjusted_asset"), rs.getTimestamp("account_reset_at")));
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
        LocalDate thisWeek = LocalDate.now(clock.withZone(TITLE_ZONE)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeek = thisWeek.minusWeeks(1);
        transactions.executeWithoutResult(status -> {
            int claimed = jdbc.update("UPDATE weekly_return_weeks SET awarded_at = ? WHERE week_start = ? AND awarded_at IS NULL",
                    now(), java.sql.Date.valueOf(lastWeek));
            if (claimed > 0) awardWeek(lastWeek, thisWeek);
        });
        transactions.executeWithoutResult(status -> {
            int created = jdbc.update("INSERT IGNORE INTO weekly_return_weeks (week_start, snapshot_at) VALUES (?, ?)",
                    java.sql.Date.valueOf(thisWeek), now());
            if (created == 0) return;
            for (InvestorAsset investor : investorAssets())
                jdbc.update("INSERT IGNORE INTO weekly_return_snapshots (week_start, user_id, baseline_asset) VALUES (?, ?, ?)",
                        java.sql.Date.valueOf(thisWeek), investor.userId, investor.adjustedAsset);
        });
    }

    private void awardWeek(LocalDate weekStart, LocalDate weekEnd) {
        Instant from = weekStart.atStartOfDay(TITLE_ZONE).toInstant();
        Instant to = weekEnd.atStartOfDay(TITLE_ZONE).toInstant();
        Map<Long, Long> baselines = new HashMap<>();
        jdbc.query("SELECT user_id, baseline_asset FROM weekly_return_snapshots WHERE week_start = ?",
                rs -> { baselines.put(rs.getLong(1), rs.getLong(2)); }, java.sql.Date.valueOf(weekStart));
        Set<Long> traded = new java.util.HashSet<>(jdbc.queryForList("""
                SELECT buyer_id FROM trades WHERE created_at >= ? AND created_at < ?
                UNION
                SELECT seller_id FROM trades WHERE created_at >= ? AND created_at < ?
                """, Long.class, Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to)));
        List<double[]> returns = new ArrayList<>();
        for (InvestorAsset investor : investorAssets()) {
            // Only investors who traded that week compete for the weekly titles.
            if (!traded.contains(investor.userId)) continue;
            // Accounts created or reset during the week start from the initial cash.
            boolean resetDuringWeek = investor.resetAt != null && !investor.resetAt.toInstant().isBefore(from);
            long baseline = resetDuringWeek ? STARTING_CASH : baselines.getOrDefault(investor.userId, STARTING_CASH);
            if (baseline <= 0) continue;
            returns.add(new double[]{investor.userId, (investor.adjustedAsset - baseline) * 100.0 / baseline});
        }
        returns.sort(Comparator.<double[]>comparingDouble(entry -> -entry[1]).thenComparingDouble(entry -> entry[0]));
        for (int index = 0; index < Math.min(3, returns.size()); index++) {
            long userId = (long) returns.get(index)[0];
            int rank = index + 1;
            // "N위 이내" titles: 1st place also earns the 2nd and 3rd place titles.
            for (int title = rank; title <= 3; title++) awardWeekly(userId, "weekly_" + title);
        }
        log.info("{} 주간 수익률 칭호 지급 완료 ({}명 참여)", weekStart, returns.size());
    }

    /** First win starts at 1관왕; every later win adds one. */
    private void awardWeekly(long userId, String titleId) {
        jdbc.update("""
                INSERT INTO user_titles (user_id, title_id, win_count, notified, acquired_at, updated_at)
                VALUES (?, ?, 1, FALSE, ?, ?)
                ON DUPLICATE KEY UPDATE win_count = win_count + 1, notified = FALSE, updated_at = VALUES(updated_at)
                """, userId, titleId, now(), now());
    }

    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private static String iso(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant().toString(); }

    private static final class Stats {
        long cash, reservedCash, unsettledCash, assetValue, totalAsset, rewardCash, maxPositionValue;
        int maxQuantity, heldStocks, maxStreak;
        boolean nicknameChanged, investor;

        double returnPercent() { return (totalAsset - STARTING_CASH - rewardCash) * 100.0 / STARTING_CASH; }
    }

    private record InvestorAsset(long userId, long adjustedAsset, Timestamp resetAt) { }

    private record Population(Map<Long, Integer> positionFromBottom, int size, long loadedAt) {
        /** True when the user is within the worst {@code share} of investors (at least one full slot). */
        boolean inBottom(long userId, double share) {
            Integer position = positionFromBottom.get(userId);
            int slots = (int) Math.floor(size * share);
            return position != null && slots >= 1 && position <= slots;
        }
    }
}
