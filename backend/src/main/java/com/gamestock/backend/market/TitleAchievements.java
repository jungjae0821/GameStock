package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.gamestock.backend.market.TitleService.INVESTOR_FILTER;
import static com.gamestock.backend.market.TitleService.InvestorAsset;
import static com.gamestock.backend.market.TitleService.STARTING_CASH;
import static com.gamestock.backend.market.TitleService.TITLES;

/**
 * One-time achievement titles: reads a user's owned titles and stats, decides which
 * titles now hold and grants them with a single INSERT IGNORE each.
 *
 * <p>A package-private collaborator built by {@link TitleService}; it is not a Spring bean.
 * It keeps the short bottom-percentile population cache (a single volatile reference, as
 * before the split) and shares the service's JdbcTemplate, clock, admin runtime and
 * investor-asset query.
 */
final class TitleAchievements {
    /** Bottom-percentile cutoffs need the whole investor population; reuse it briefly. */
    private static final long POPULATION_CACHE_MILLIS = 60_000L;

    private static final long[] ASSET_THRESHOLDS = {
            30_000_000L, 50_000_000L, 100_000_000L, 1_000_000_000L, 3_000_000_000L,
            5_000_000_000L, 10_000_000_000L, 100_000_000_000L};
    private static final String[] ASSET_TITLE_IDS = {
            "asset_30m", "asset_50m", "asset_100m", "asset_1b", "asset_3b", "asset_5b", "asset_10b", "asset_100b"};

    private final JdbcTemplate jdbc;
    private final Supplier<Clock> clockSource;
    private final Supplier<AdminRuntimeControl> adminRuntimeSource;
    private final Supplier<List<InvestorAsset>> investorAssets;
    private volatile Population population;

    TitleAchievements(JdbcTemplate jdbc, Supplier<Clock> clock, Supplier<AdminRuntimeControl> adminRuntime,
                      Supplier<List<InvestorAsset>> investorAssets) {
        this.jdbc = jdbc;
        this.clockSource = clock;
        this.adminRuntimeSource = adminRuntime;
        this.investorAssets = investorAssets;
    }

    private Clock clock() { return clockSource.get(); }

    private List<InvestorAsset> investorAssets() { return investorAssets.get(); }

    /** Grants every one-time title whose condition currently holds; {@code owned} is updated in place. */
    void grantEarned(long userId, Set<String> owned) {
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

    boolean grantIf(long userId, Set<String> owned, String titleId, boolean condition) {
        if (!condition || owned.contains(titleId)) return false;
        jdbc.update("INSERT IGNORE INTO user_titles (user_id, title_id, acquired_at, updated_at) VALUES (?, ?, ?, ?)",
                userId, titleId, now(), now());
        owned.add(titleId);
        return true;
    }

    Set<String> ownedIds(long userId) {
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
        AdminRuntimeControl adminRuntime = adminRuntimeSource.get();
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
        long nowMillis = clock().millis();
        if (current != null && nowMillis - current.loadedAt < POPULATION_CACHE_MILLIS) return current;
        List<long[]> returns = new ArrayList<>();
        for (InvestorAsset investor : investorAssets()) {
            // Return in basis points so ties compare exactly.
            long basisPoints = Math.round((investor.adjustedAsset() - STARTING_CASH) * 10_000.0 / STARTING_CASH);
            returns.add(new long[]{investor.userId(), basisPoints});
        }
        // Worst return first; on a tie the newer account counts as lower.
        returns.sort(Comparator.<long[]>comparingLong(entry -> entry[1]).thenComparingLong(entry -> -entry[0]));
        Map<Long, Integer> positionFromBottom = new HashMap<>();
        for (int index = 0; index < returns.size(); index++) positionFromBottom.put(returns.get(index)[0], index + 1);
        current = new Population(positionFromBottom, returns.size(), nowMillis);
        population = current;
        return current;
    }

    private Timestamp now() { return Timestamp.from(clock().instant()); }

    private static final class Stats {
        long cash, loanBalance, reservedCash, unsettledCash, assetValue, totalAsset, rewardCash, maxPositionValue;
        int maxQuantity, heldStocks, maxStreak;
        boolean nicknameChanged, investor;

        double returnPercent() { return (totalAsset - STARTING_CASH - rewardCash) * 100.0 / STARTING_CASH; }
    }

    private record Population(Map<Long, Integer> positionFromBottom, int size, long loadedAt) {
        /** True when the user is within the worst {@code share} of investors (at least one full slot). */
        boolean inBottom(long userId, double share) {
            Integer position = positionFromBottom.get(userId);
            int slots = (int) Math.floor(size * share);
            return position != null && slots >= 1 && position <= slots;
        }
    }
}
