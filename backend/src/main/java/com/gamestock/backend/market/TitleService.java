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
import java.util.HashMap;
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
 *
 * <p>One-time title evaluation and grants live in {@link TitleAchievements}, and the
 * table DDL in {@link TitleSchema}; both are built here as well.
 */
@Service
public class TitleService {
    private static final Logger log = LoggerFactory.getLogger(TitleService.class);
    static final long STARTING_CASH = 1_000_000L;
    static final ZoneId TITLE_ZONE = ZoneId.of("Asia/Seoul");
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
    private final WeeklyTitleAwards weekly;
    private final TitleAchievements achievements;
    private final TitleSchema schema;

    public TitleService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.weekly = new WeeklyTitleAwards(jdbc, transactions, () -> this.clock, this::investorAssets);
        this.achievements = new TitleAchievements(jdbc, () -> this.clock, () -> this.adminRuntime, this::investorAssets);
        this.schema = new TitleSchema(jdbc);
    }

    void useClock(Clock clock) { this.clock = clock; }

    void ensureTables() {
        schema.ensureTables();
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
        achievements.grantEarned(userId, owned);
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

    private boolean grantIf(long userId, Set<String> owned, String titleId, boolean condition) {
        return achievements.grantIf(userId, owned, titleId, condition);
    }

    private Set<String> ownedIds(long userId) {
        return achievements.ownedIds(userId);
    }

    /** Every investor's return-basis asset by user id, for fandom return averages. */
    Map<Long, Long> adjustedAssetsByUser() {
        Map<Long, Long> result = new HashMap<>();
        for (InvestorAsset asset : investorAssets()) result.put(asset.userId(), asset.adjustedAsset());
        return result;
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

    private static String iso(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant().toString(); }

    record InvestorAsset(long userId, long adjustedAsset, Timestamp resetAt) { }
}
