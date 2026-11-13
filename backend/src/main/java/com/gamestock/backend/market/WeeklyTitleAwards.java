package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.gamestock.backend.market.TitleService.INVESTOR_FILTER;
import static com.gamestock.backend.market.TitleService.STARTING_CASH;
import static com.gamestock.backend.market.TitleService.TITLE_ZONE;
import static com.gamestock.backend.market.TitleService.InvestorAsset;
import static com.gamestock.backend.market.TitleService.TitleDefinition;
import static com.gamestock.backend.market.TitleService.WeeklyRanking;
import static com.gamestock.backend.market.TitleService.WeeklyRankingEntry;

/**
 * Weekly return-ranking titles: closing snapshots, the Monday award and the live ranking.
 *
 * <p>A package-private collaborator built by {@link TitleService}; it is not a Spring bean.
 * It holds no monitor (neither did the code before the split): the award runs once per replica
 * through the claim columns, and the live-ranking cache is a single volatile reference.
 * It shares the service's JdbcTemplate, TransactionTemplate and clock.
 */
final class WeeklyTitleAwards {
    private static final Logger log = LoggerFactory.getLogger(TitleService.class);
    /** Every ranking page polls the weekly ranking; one computation serves them all briefly. */
    private static final long WEEKLY_RANKING_CACHE_MILLIS = 10_000L;
    private static final int WEEKLY_RANKING_LIMIT = 100;
    /** Closing assets are refreshed on every tick within this span before the week ends. */
    private static final Duration CLOSING_WINDOW = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Supplier<Clock> clockSource;
    private final Supplier<List<InvestorAsset>> investorAssets;
    private volatile LiveWeek liveWeek;

    WeeklyTitleAwards(JdbcTemplate jdbc, TransactionTemplate transactions, Supplier<Clock> clock,
                      Supplier<List<InvestorAsset>> investorAssets) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clockSource = clock;
        this.investorAssets = investorAssets;
    }

    private Clock clock() { return clockSource.get(); }

    private List<InvestorAsset> investorAssets() { return investorAssets.get(); }

    void awardWeeklyTitles() {
        LocalDate thisWeek = LocalDate.now(clock().withZone(TITLE_ZONE)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeek = thisWeek.minusWeeks(1);
        recordClosingAssets(thisWeek);
        transactions.executeWithoutResult(status -> {
            int claimed = jdbc.update("UPDATE weekly_return_weeks SET awarded_at = ? WHERE week_start = ? AND awarded_at IS NULL",
                    now(), java.sql.Date.valueOf(lastWeek));
            if (claimed > 0) awardWeek(lastWeek, thisWeek);
        });
        transactions.executeWithoutResult(status -> {
            int created = jdbc.update("INSERT IGNORE INTO weekly_return_weeks (week_start, snapshot_at) VALUES (?, ?)",
                    java.sql.Date.valueOf(thisWeek), now());
            if (created == 0) return;
            // The new week starts where last week closed, so the stretch between the
            // closing snapshot and this run is not dropped from both weeks.
            Closing previous = closing(lastWeek);
            for (InvestorAsset investor : investorAssets())
                jdbc.update("INSERT IGNORE INTO weekly_return_snapshots (week_start, user_id, baseline_asset) VALUES (?, ?, ?)",
                        java.sql.Date.valueOf(thisWeek), investor.userId(),
                        previous == null ? investor.adjustedAsset() : previous.assets.getOrDefault(investor.userId(), investor.adjustedAsset()));
        });
    }

    /**
     * In the week's last hour, stores every investor's current asset as the week's
     * closing asset. The Monday award ranks these, so trades and price moves after
     * the last refresh before midnight do not count, however late the award runs.
     */
    private void recordClosingAssets(LocalDate weekStart) {
        Instant now = clock().instant();
        Instant end = weekStart.plusWeeks(1).atStartOfDay(TITLE_ZONE).toInstant();
        if (now.isBefore(end.minus(CLOSING_WINDOW)) || !now.isBefore(end)) return;
        List<InvestorAsset> investors = investorAssets();
        transactions.executeWithoutResult(status -> {
            if (jdbc.update("UPDATE weekly_return_weeks SET closing_at = ? WHERE week_start = ?", Timestamp.from(now), java.sql.Date.valueOf(weekStart)) == 0)
                return;
            // Accounts without a baseline row start from the initial cash, as weeklyReturns assumes.
            jdbc.batchUpdate("""
                    INSERT INTO weekly_return_snapshots (week_start, user_id, baseline_asset, closing_asset) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE closing_asset = VALUES(closing_asset)
                    """, investors.stream().map(investor -> new Object[]{java.sql.Date.valueOf(weekStart), investor.userId(),
                    STARTING_CASH, investor.adjustedAsset()}).toList());
        });
    }

    /** The week's last closing snapshot, or null when none was taken before it ended. */
    private Closing closing(LocalDate weekStart) {
        Timestamp at = jdbc.query("SELECT closing_at FROM weekly_return_weeks WHERE week_start = ?",
                (rs, row) -> rs.getTimestamp(1), java.sql.Date.valueOf(weekStart)).stream()
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (at == null) return null;
        Map<Long, Long> assets = new HashMap<>();
        jdbc.query("SELECT user_id, closing_asset FROM weekly_return_snapshots WHERE week_start = ? AND closing_asset IS NOT NULL",
                rs -> { assets.put(rs.getLong(1), rs.getLong(2)); }, java.sql.Date.valueOf(weekStart));
        return new Closing(at.toInstant(), assets);
    }

    private void awardWeek(LocalDate weekStart, LocalDate weekEnd) {
        Closing closing = closing(weekStart);
        if (closing == null)
            log.warn("{} 주간 마감 자산이 없어 지급 시점 자산으로 주간 칭호를 계산합니다.", weekStart);
        List<WeeklyReturn> returns = closing == null
                ? weeklyReturns(weekStart, weekEnd.atStartOfDay(TITLE_ZONE).toInstant(), null)
                : weeklyReturns(weekStart, closing.at, closing.assets);
        // Operating personas stay in the live ranking but never take a weekly title; the
        // members below them move up for the award.
        List<Long> personaRows = jdbc.queryForList("SELECT id FROM users WHERE persona IS NOT NULL", Long.class);
        Set<Long> personas = personaRows == null ? Set.of() : new java.util.HashSet<>(personaRows);
        int rank = 0;
        for (WeeklyReturn entry : returns) {
            if (rank >= 3) break;
            if (personas.contains(entry.userId)) continue;
            rank++;
            // "N위 이내" titles: 1st place also earns the 2nd and 3rd place titles.
            for (int title = rank; title <= 3; title++) awardWeekly(entry.userId, "weekly_" + title);
        }
        log.info("{} 주간 수익률 칭호 지급 완료 ({}명 참여)", weekStart, returns.size());
    }

    /**
     * Returns from the week's baseline, best first. Only investors who traded between
     * the week start and {@code to} take part; the Monday award and the live ranking share this.
     * With {@code closingAssets} the returns use those stored assets instead of the live ones,
     * and investors without a closing asset did not exist when the week closed.
     */
    private List<WeeklyReturn> weeklyReturns(LocalDate weekStart, Instant to, Map<Long, Long> closingAssets) {
        Instant from = weekStart.atStartOfDay(TITLE_ZONE).toInstant();
        Map<Long, Long> baselines = new HashMap<>();
        jdbc.query("SELECT user_id, baseline_asset FROM weekly_return_snapshots WHERE week_start = ?",
                rs -> { baselines.put(rs.getLong(1), rs.getLong(2)); }, java.sql.Date.valueOf(weekStart));
        // Per-user EXISTS probes ride the (buyer_id|seller_id, created_at) indexes instead of scanning the week.
        Set<Long> traded = new java.util.HashSet<>(jdbc.queryForList("""
                SELECT u.id FROM users u
                WHERE (EXISTS (SELECT 1 FROM trades t WHERE t.buyer_id = u.id AND t.created_at >= ? AND t.created_at < ?)
                   OR EXISTS (SELECT 1 FROM trades t WHERE t.seller_id = u.id AND t.created_at >= ? AND t.created_at < ?))
                  AND """ + " " + INVESTOR_FILTER, Long.class, Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to)));
        List<WeeklyReturn> returns = new ArrayList<>();
        for (InvestorAsset investor : investorAssets()) {
            // Only investors who traded that week compete for the weekly titles.
            if (!traded.contains(investor.userId())) continue;
            Long asset = closingAssets == null ? Long.valueOf(investor.adjustedAsset()) : closingAssets.get(investor.userId());
            if (asset == null) continue;
            // Accounts created or reset during the week start from the initial cash. A reset
            // after the close leaves the stored closing asset as it was.
            boolean resetDuringWeek = investor.resetAt() != null && !investor.resetAt().toInstant().isBefore(from)
                    && investor.resetAt().toInstant().isBefore(to);
            long baseline = resetDuringWeek ? STARTING_CASH : baselines.getOrDefault(investor.userId(), STARTING_CASH);
            if (baseline <= 0) continue;
            returns.add(new WeeklyReturn(investor.userId(), (asset - baseline) * 100.0 / baseline));
        }
        returns.sort(Comparator.<WeeklyReturn>comparingDouble(entry -> -entry.percent).thenComparingLong(entry -> entry.userId));
        return returns;
    }

    /**
     * The running Korean week's return ranking, ranked the way Monday's award will rank
     * it. The population is shared and cached briefly; only the viewer's row is per request.
     */
    WeeklyRanking weeklyRanking(Long viewerId) {
        LocalDate weekStart = LocalDate.now(clock().withZone(TITLE_ZONE)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LiveWeek current = liveWeek;
        long nowMillis = clock().millis();
        if (current == null || !current.weekStart.equals(weekStart) || nowMillis - current.loadedAt >= WEEKLY_RANKING_CACHE_MILLIS) {
            current = new LiveWeek(weekStart, weeklyReturns(weekStart, clock().instant(), null), nowMillis);
            liveWeek = current;
        }
        List<WeeklyReturn> returns = current.returns;
        int viewerIndex = -1;
        if (viewerId != null)
            for (int index = 0; index < returns.size(); index++)
                if (returns.get(index).userId == viewerId) { viewerIndex = index; break; }
        int shown = Math.min(WEEKLY_RANKING_LIMIT, returns.size());
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < shown; index++) ids.add(returns.get(index).userId);
        if (viewerIndex >= shown) ids.add(returns.get(viewerIndex).userId);
        Map<Long, WeeklyRankingEntry> display = rankingDisplay(ids);
        List<WeeklyRankingEntry> entries = new ArrayList<>(shown);
        for (int index = 0; index < shown; index++) {
            WeeklyRankingEntry entry = rankedEntry(display, returns.get(index), index + 1);
            if (entry != null) entries.add(entry);
        }
        WeeklyRankingEntry me = viewerIndex < 0 ? null : rankedEntry(display, returns.get(viewerIndex), viewerIndex + 1);
        return new WeeklyRanking(weekStart.toString(), weekStart.plusDays(6).toString(),
                weekStart.plusWeeks(1).atStartOfDay(TITLE_ZONE).toInstant().toString(), returns.size(), entries, me);
    }

    private static WeeklyRankingEntry rankedEntry(Map<Long, WeeklyRankingEntry> display, WeeklyReturn result, int rank) {
        WeeklyRankingEntry base = display.get(result.userId);
        return base == null ? null : new WeeklyRankingEntry(rank, base.nickname(), base.profileImageUrl(), base.totalAsset(),
                result.percent, base.equippedTitle(), base.equippedTitleWeekly());
    }

    /** Nickname, avatar, equipped title and the same net asset the overall ranking shows. */
    private Map<Long, WeeklyRankingEntry> rankingDisplay(List<Long> userIds) {
        Map<Long, WeeklyRankingEntry> display = new HashMap<>();
        if (userIds.isEmpty()) return display;
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        jdbc.query("""
                SELECT u.id, u.nickname, u.profile_image_url,
                       """ + AccountValuation.GROSS_ASSET + """
                       - u.loan_balance AS total_asset,
                       (SELECT ut.title_id FROM user_titles ut WHERE ut.user_id = u.id AND ut.equipped = TRUE LIMIT 1) AS equipped_title,
                       (SELECT ut.win_count FROM user_titles ut WHERE ut.user_id = u.id AND ut.equipped = TRUE LIMIT 1) AS equipped_count
                FROM users u WHERE u.id IN (""" + placeholders + ")", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            String image = com.gamestock.backend.auth.ProfileImages.sanitize(rs.getString("profile_image_url"));
            TitleDefinition title = TitleService.definition(rs.getString("equipped_title"));
            display.put(rs.getLong("id"), new WeeklyRankingEntry(0, rs.getString("nickname"), image.isEmpty() ? null : image,
                    rs.getLong("total_asset"), 0, title == null ? null : TitleService.displayName(title, rs.getInt("equipped_count")),
                    title != null && title.weekly()));
        }, userIds.toArray());
        return display;
    }

    /** First win starts at 1관왕; every later win adds one. */
    private void awardWeekly(long userId, String titleId) {
        jdbc.update("""
                INSERT INTO user_titles (user_id, title_id, win_count, notified, acquired_at, updated_at)
                VALUES (?, ?, 1, FALSE, ?, ?)
                ON DUPLICATE KEY UPDATE win_count = win_count + 1, notified = FALSE, updated_at = VALUES(updated_at)
                """, userId, titleId, now(), now());
    }

    private Timestamp now() { return Timestamp.from(clock().instant()); }

    private record WeeklyReturn(long userId, double percent) { }
    private record Closing(Instant at, Map<Long, Long> assets) { }
    private record LiveWeek(LocalDate weekStart, List<WeeklyReturn> returns, long loadedAt) { }
}
