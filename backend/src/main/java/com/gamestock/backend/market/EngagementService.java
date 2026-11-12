package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fan-facing features: a favourite game per member, the fandom board, the season
 * ranking, opt-in public profiles, the live trade feed, the daily prediction and the
 * comeback fund.
 *
 * <p>Every cash reward here is written to {@code admin_cash_adjustments} the same way
 * promotion codes are, so ranking and season returns leave it out.
 */
@Service
public class EngagementService {
    private static final Logger log = LoggerFactory.getLogger(EngagementService.class);
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final long PREDICTION_REWARD = 20_000L;
    static final int PREDICTION_CUTOFF_HOUR = 22;
    static final long RESCUE_AMOUNT = 500_000L;
    static final long RESCUE_LIMIT = 100_000L;
    static final Duration RESCUE_COOLDOWN = Duration.ofDays(7);
    static final Duration FANDOM_COOLDOWN = Duration.ofDays(7);
    static final long WHALE_AMOUNT = 30_000_000L;
    private static final String NET_ASSET = "(" + AccountValuation.GROSS_ASSET + " - u.loan_balance)";

    public record Me(String fandomCode, String fandomChangeAvailableAt, boolean publicProfile, Prediction prediction,
                     Rescue rescue, FeatureUnlockService.Progress unlocks) { }
    public record Prediction(String day, String stockCode, long basePrice, Long closePrice, String result, long reward,
                             boolean open, String closesAt, Prediction previous) { }
    public record Rescue(boolean available, long amount, long netAsset, long limit, String nextAt) { }
    public record Fandom(String stockCode, String name, int fans, int holders, Double weeklyReturnPercent, int rank) { }
    public record SeasonEntry(int rank, String nickname, String profileImageUrl, long openingAsset, long currentAsset,
                              double returnPercent, boolean publicProfile) { }
    public record Season(long id, String name, String status, String startedAt, String endedAt, int participants,
                         List<SeasonEntry> entries, SeasonEntry me) { }
    public record Holding(String stockCode, String name, long quantity, long averagePrice, long currentPrice,
                          long value, double returnPercent) { }
    public record PublicProfile(String nickname, String profileImageUrl, String equippedTitle, String fandomCode,
                                long totalAsset, List<Holding> holdings) { }
    public record FeedItem(long id, String stockCode, String name, String side, long quantity, long price, long amount,
                           String nickname, boolean whale, String at) { }
    public record FandomRequest(String stockCode) { }
    public record PublicRequest(boolean publicProfile) { }
    public record PredictionRequest(String stockCode) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FeatureUnlockService unlocks;
    private final TitleService titles;
    private Clock clock = Clock.systemUTC();
    private final AtomicReference<Cached<List<Fandom>>> fandomCache = new AtomicReference<>();
    private final AtomicReference<Cached<List<FeedItem>>> feedCache = new AtomicReference<>();
    private final AtomicReference<Cached<List<String>>> publicCache = new AtomicReference<>();
    private record Cached<T>(Instant at, T value) { }

    public EngagementService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                             FeatureUnlockService unlocks, TitleService titles) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.unlocks = unlocks;
        this.titles = titles;
    }

    void useClock(Clock clock) { this.clock = clock; this.unlocks.useClock(clock); }

    // ── 내 상태 ──────────────────────────────────────────────

    public Me me(long userId) {
        var rows = jdbc.queryForList("""
                SELECT s.stock_code, e.public_profile, e.fandom_changed_at FROM user_engagement e
                LEFT JOIN stocks s ON s.id = e.fandom_stock_id WHERE e.user_id = ?
                """, userId);
        String fandom = rows.isEmpty() ? null : (String) rows.get(0).get("stock_code");
        boolean open = !rows.isEmpty() && truthy(rows.get(0).get("public_profile"));
        String changeAt = null;
        if (!rows.isEmpty() && rows.get(0).get("fandom_changed_at") instanceof Timestamp changed) {
            Instant next = changed.toInstant().plus(FANDOM_COOLDOWN);
            if (next.isAfter(clock.instant())) changeAt = next.toString();
        }
        return new Me(fandom, changeAt, open, prediction(userId), rescue(userId), unlocks.progress(userId));
    }

    public Me setFandom(long userId, FandomRequest request) {
        long stockId = stockId(request == null ? null : request.stockCode());
        transactions.executeWithoutResult(status -> {
            ensureRow(userId);
            var rows = jdbc.queryForList("SELECT fandom_stock_id, fandom_changed_at FROM user_engagement WHERE user_id=? FOR UPDATE", userId);
            var row = rows.get(0);
            if (row.get("fandom_stock_id") instanceof Number current && current.longValue() == stockId) return;
            // The first pick is free; switching afterwards waits a week so fandom battles stay meaningful.
            if (row.get("fandom_stock_id") != null && row.get("fandom_changed_at") instanceof Timestamp changed
                    && changed.toInstant().plus(FANDOM_COOLDOWN).isAfter(clock.instant()))
                throw new IllegalArgumentException("주력 게임은 일주일에 한 번만 바꿀 수 있어요.");
            jdbc.update("UPDATE user_engagement SET fandom_stock_id=?, fandom_changed_at=?, updated_at=? WHERE user_id=?",
                    stockId, row.get("fandom_stock_id") == null ? null : now(), now(), userId);
        });
        fandomCache.set(null);
        return me(userId);
    }

    public Me setPublic(long userId, PublicRequest request) {
        ensureRow(userId);
        jdbc.update("UPDATE user_engagement SET public_profile=?, updated_at=? WHERE user_id=?",
                request != null && request.publicProfile(), now(), userId);
        feedCache.set(null);
        publicCache.set(null);
        return me(userId);
    }

    // ── 팬덤 대항전 ──────────────────────────────────────────

    /** Fans, holders and the fans' average return this week for every listed stock. Cached for 30 seconds. */
    public List<Fandom> fandoms() {
        var cached = fandomCache.get();
        if (cached != null && cached.at().plusSeconds(30).isAfter(clock.instant())) return cached.value();
        Map<Long, int[]> counts = new HashMap<>();
        Map<Long, String[]> names = new HashMap<>();
        jdbc.query("SELECT s.id, s.stock_code, g.name FROM stocks s JOIN games g ON g.id = s.game_id ORDER BY s.id", rs -> {
            names.put(rs.getLong(1), new String[]{rs.getString(2), rs.getString(3)});
            counts.put(rs.getLong(1), new int[2]);
        });
        jdbc.query("SELECT e.fandom_stock_id, COUNT(*) FROM user_engagement e JOIN users u ON u.id = e.user_id WHERE e.fandom_stock_id IS NOT NULL AND "
                + TitleService.INVESTOR_FILTER + " GROUP BY e.fandom_stock_id", rs -> {
            int[] c = counts.get(rs.getLong(1));
            if (c != null) c[0] = rs.getInt(2);
        });
        jdbc.query("SELECT p.stock_id, COUNT(*) FROM portfolios p JOIN users u ON u.id = p.user_id WHERE p.quantity > 0 AND "
                + TitleService.INVESTOR_FILTER + " GROUP BY p.stock_id", rs -> {
            int[] c = counts.get(rs.getLong(1));
            if (c != null) c[1] = rs.getInt(2);
        });
        Map<Long, double[]> returns = new HashMap<>();
        Map<Long, Long> assets = titles.adjustedAssetsByUser();
        jdbc.query("""
                SELECT e.fandom_stock_id, w.user_id, w.baseline_asset FROM user_engagement e
                JOIN weekly_return_snapshots w ON w.user_id = e.user_id
                WHERE e.fandom_stock_id IS NOT NULL AND w.week_start = (SELECT MAX(week_start) FROM weekly_return_weeks)
                """, rs -> {
            long baseline = rs.getLong(3);
            Long current = assets.get(rs.getLong(2));
            if (baseline <= 0 || current == null) return;
            double[] sum = returns.computeIfAbsent(rs.getLong(1), id -> new double[2]);
            sum[0] += (current - baseline) * 100.0 / baseline;
            sum[1]++;
        });
        List<Fandom> list = new ArrayList<>();
        for (var entry : names.entrySet()) {
            int[] c = counts.get(entry.getKey());
            double[] r = returns.get(entry.getKey());
            Double weekly = r == null || r[1] == 0 ? null : Math.round(r[0] / r[1] * 100.0) / 100.0;
            list.add(new Fandom(entry.getValue()[0], entry.getValue()[1], c[0], c[1], weekly, 0));
        }
        list.sort(Comparator.comparingInt(Fandom::fans).reversed().thenComparing(Fandom::holders, Comparator.reverseOrder())
                .thenComparing(Fandom::stockCode));
        List<Fandom> ranked = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Fandom f = list.get(i);
            ranked.add(new Fandom(f.stockCode(), f.name(), f.fans(), f.holders(), f.weeklyReturnPercent(), i + 1));
        }
        List<Fandom> result = List.copyOf(ranked);
        fandomCache.set(new Cached<>(clock.instant(), result));
        return result;
    }

    // ── 시즌 랭킹 ────────────────────────────────────────────

    /**
     * The running season ranked live the way {@code AdminSeasonService.close} will rank it:
     * gain over the opening asset with admin cash paid since the start removed. Without an
     * active season, the most recently closed one is returned with its final ranks.
     */
    public Season season(Long viewerId) {
        var seasons = jdbc.queryForList("SELECT * FROM admin_seasons WHERE status IN ('ACTIVE','CLOSED','RESET') ORDER BY (status='ACTIVE') DESC, id DESC LIMIT 1");
        if (seasons.isEmpty()) return null;
        var season = seasons.get(0);
        long id = ((Number) season.get("id")).longValue();
        String status = String.valueOf(season.get("status"));
        List<SeasonEntry> all = new ArrayList<>();
        Map<Long, Integer> positions = new HashMap<>();
        if ("ACTIVE".equals(status)) {
            List<long[]> raw = new ArrayList<>();
            Map<Long, String[]> meta = new HashMap<>();
            jdbc.query("SELECT e.user_id, e.opening_asset, " + NET_ASSET + " AS asset, "
                    + "COALESCE((SELECT SUM(c.amount) FROM admin_cash_adjustments c WHERE c.user_id=e.user_id AND c.created_at>=s.started_at),0) AS external_cash, "
                    + "u.nickname, u.profile_image_url, COALESCE(ue.public_profile,FALSE) AS public_profile "
                    + "FROM admin_season_entries e JOIN admin_seasons s ON s.id=e.season_id JOIN users u ON u.id=e.user_id "
                    + "LEFT JOIN user_engagement ue ON ue.user_id=e.user_id WHERE e.season_id=?", rs -> {
                long user = rs.getLong("user_id");
                raw.add(new long[]{user, rs.getLong("opening_asset"), rs.getLong("asset"), rs.getLong("external_cash")});
                meta.put(user, new String[]{rs.getString("nickname"), rs.getString("profile_image_url"), rs.getBoolean("public_profile") ? "1" : "0"});
            }, id);
            raw.sort(Comparator.<long[]>comparingLong(r -> r[2] - r[1] - r[3]).reversed().thenComparingLong(r -> r[0]));
            int rank = 0;
            for (long[] r : raw) {
                rank++;
                String[] m = meta.get(r[0]);
                long current = r[2] - r[3];
                all.add(new SeasonEntry(rank, m[0], m[1], r[1], current, percent(current - r[1], r[1]), "1".equals(m[2])));
                positions.put(r[0], rank - 1);
            }
        } else {
            jdbc.query("SELECT e.user_id, e.rank_no, e.nickname, e.opening_asset, e.closing_asset, u.profile_image_url, "
                    + "COALESCE(ue.public_profile,FALSE) AS public_profile FROM admin_season_entries e JOIN users u ON u.id=e.user_id "
                    + "LEFT JOIN user_engagement ue ON ue.user_id=e.user_id WHERE e.season_id=? AND e.rank_no IS NOT NULL ORDER BY e.rank_no", rs -> {
                long opening = rs.getLong("opening_asset");
                long closing = rs.getLong("closing_asset");
                positions.put(rs.getLong("user_id"), all.size());
                all.add(new SeasonEntry(rs.getInt("rank_no"), rs.getString("nickname"), rs.getString("profile_image_url"),
                        opening, closing, percent(closing - opening, opening), rs.getBoolean("public_profile")));
            }, id);
        }
        SeasonEntry me = viewerId == null || !positions.containsKey(viewerId) ? null : all.get(positions.get(viewerId));
        return new Season(id, String.valueOf(season.get("name")), status, iso(season.get("started_at")), iso(season.get("ended_at")),
                all.size(), all.subList(0, Math.min(100, all.size())), me);
    }

    // ── 공개 프로필 ──────────────────────────────────────────

    public PublicProfile profile(String nickname) {
        var rows = jdbc.queryForList("""
                SELECT u.id, u.nickname, u.profile_image_url, s.stock_code AS fandom_code,
                       (SELECT ut.title_id FROM user_titles ut WHERE ut.user_id=u.id AND ut.equipped=TRUE LIMIT 1) AS title_id,
                       (SELECT ut.win_count FROM user_titles ut WHERE ut.user_id=u.id AND ut.equipped=TRUE LIMIT 1) AS title_count
                FROM users u JOIN user_engagement e ON e.user_id=u.id LEFT JOIN stocks s ON s.id=e.fandom_stock_id
                WHERE u.nickname=? AND e.public_profile=TRUE
                """, nickname == null ? "" : nickname.trim());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "공개하지 않은 프로필이에요.");
        var row = rows.get(0);
        long userId = ((Number) row.get("id")).longValue();
        var title = TitleService.definition((String) row.get("title_id"));
        String titleName = title == null ? null : TitleService.displayName(title, row.get("title_count") instanceof Number n ? n.intValue() : 1);
        List<Holding> holdings = jdbc.query("""
                SELECT s.stock_code, g.name, p.quantity, p.average_price, COALESCE(pm.mark_price, s.current_price) AS price
                FROM portfolios p JOIN stocks s ON s.id=p.stock_id JOIN games g ON g.id=s.game_id
                LEFT JOIN market_price_metrics pm ON pm.stock_id=s.id
                WHERE p.user_id=? AND p.quantity>0 ORDER BY p.quantity*COALESCE(pm.mark_price, s.current_price) DESC LIMIT 20
                """, (rs, i) -> {
            long qty = rs.getLong("quantity"), avg = rs.getLong("average_price"), price = rs.getLong("price");
            return new Holding(rs.getString("stock_code"), rs.getString("name"), qty, avg, price, qty * price, percent(price - avg, avg));
        }, userId);
        Long total = jdbc.queryForObject("SELECT " + NET_ASSET + " FROM users u WHERE u.id=?", Long.class, userId);
        return new PublicProfile((String) row.get("nickname"), (String) row.get("profile_image_url"), titleName,
                (String) row.get("fandom_code"), total == null ? 0 : total, holdings);
    }

    /** Nicknames of members who opened their portfolio, so ranking rows know which names to link. Cached for 30 seconds. */
    public List<String> publicNicknames() {
        var cached = publicCache.get();
        if (cached != null && cached.at().plusSeconds(30).isAfter(clock.instant())) return cached.value();
        List<String> names = List.copyOf(jdbc.queryForList(
                "SELECT u.nickname FROM user_engagement e JOIN users u ON u.id=e.user_id WHERE e.public_profile=TRUE ORDER BY u.id LIMIT 5000",
                String.class));
        publicCache.set(new Cached<>(clock.instant(), names));
        return names;
    }

    // ── 실시간 체결 피드 ─────────────────────────────────────

    /**
     * Recent member fills: every whale-sized fill (named only if the member is public) and
     * every fill by a public member. Bots never appear. Cached for five seconds.
     */
    public List<FeedItem> feed() {
        var cached = feedCache.get();
        if (cached != null && cached.at().plusSeconds(5).isAfter(clock.instant())) return cached.value();
        Long maxId = jdbc.queryForObject("SELECT MAX(id) FROM trades", Long.class);
        if (maxId == null) return List.of();
        String human = "%s.password_hash NOT IN ('BOT','TRADER') AND %s.username <> 'demo' AND COALESCE(%s.role,'USER') <> 'ADMIN'";
        List<FeedItem> items = new ArrayList<>();
        jdbc.query("SELECT t.id, s.stock_code, g.name, t.quantity, t.price, t.created_at, "
                + "b.nickname AS buyer, (" + human.formatted("b", "b", "b") + ") AS buyer_human, COALESCE(be.public_profile,FALSE) AS buyer_public, "
                + "x.nickname AS seller, (" + human.formatted("x", "x", "x") + ") AS seller_human, COALESCE(xe.public_profile,FALSE) AS seller_public "
                + "FROM trades t JOIN stocks s ON s.id=t.stock_id JOIN games g ON g.id=s.game_id "
                + "JOIN users b ON b.id=t.buyer_id JOIN users x ON x.id=t.seller_id "
                + "LEFT JOIN user_engagement be ON be.user_id=t.buyer_id LEFT JOIN user_engagement xe ON xe.user_id=t.seller_id "
                + "WHERE t.id > ? AND t.created_at >= ? ORDER BY t.id DESC LIMIT 400", rs -> {
            long amount = rs.getLong("quantity") * rs.getLong("price");
            boolean whale = amount >= WHALE_AMOUNT;
            for (String side : List.of("buyer", "seller")) {
                if (!rs.getBoolean(side + "_human")) continue;
                boolean open = rs.getBoolean(side + "_public");
                if (!whale && !open) continue;
                if (items.size() >= 30) return;
                items.add(new FeedItem(rs.getLong("id"), rs.getString("stock_code"), rs.getString("name"),
                        side.equals("buyer") ? "BUY" : "SELL", rs.getLong("quantity"), rs.getLong("price"), amount,
                        open ? rs.getString(side) : null, whale, rs.getTimestamp("created_at").toInstant().toString()));
            }
        }, Math.max(0, maxId - 20_000), Timestamp.from(clock.instant().minus(Duration.ofMinutes(30))));
        List<FeedItem> result = List.copyOf(items);
        feedCache.set(new Cached<>(clock.instant(), result));
        return result;
    }

    // ── 일일 예측 ────────────────────────────────────────────

    public Me predict(long userId, PredictionRequest request) {
        long stockId = stockId(request == null ? null : request.stockCode());
        ZonedDateTime local = clock.instant().atZone(ZONE);
        if (local.getHour() >= PREDICTION_CUTOFF_HOUR)
            throw new IllegalArgumentException("오늘 예측은 " + PREDICTION_CUTOFF_HOUR + "시에 마감했어요. 내일 다시 골라 주세요.");
        LocalDate day = local.toLocalDate();
        Long price = jdbc.queryForObject("SELECT current_price FROM stocks WHERE id=?", Long.class, stockId);
        int inserted = jdbc.update("INSERT IGNORE INTO daily_predictions (user_id, day, stock_id, base_price, created_at) VALUES (?, ?, ?, ?, ?)",
                userId, Date.valueOf(day), stockId, price, now());
        if (inserted == 0) throw new IllegalArgumentException("오늘은 이미 예측했어요. 자정에 결과가 나와요.");
        return me(userId);
    }

    private Prediction prediction(long userId) {
        settlePending(userId);
        LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
        var rows = jdbc.queryForList("""
                SELECT d.day, s.stock_code, d.base_price, d.close_price, d.result, d.reward_cash FROM daily_predictions d
                JOIN stocks s ON s.id=d.stock_id WHERE d.user_id=? ORDER BY d.day DESC LIMIT 2
                """, userId);
        Prediction previous = null;
        Prediction current = null;
        for (var row : rows) {
            LocalDate day = ((Date) row.get("day")).toLocalDate();
            Prediction p = new Prediction(day.toString(), (String) row.get("stock_code"), ((Number) row.get("base_price")).longValue(),
                    row.get("close_price") instanceof Number n ? n.longValue() : null, (String) row.get("result"),
                    ((Number) row.get("reward_cash")).longValue(), false, null, null);
            if (day.equals(today)) current = p;
            else if (previous == null) previous = p;
        }
        boolean open = clock.instant().atZone(ZONE).getHour() < PREDICTION_CUTOFF_HOUR;
        String closesAt = today.atTime(PREDICTION_CUTOFF_HOUR, 0).atZone(ZONE).toInstant().toString();
        if (current == null) return new Prediction(today.toString(), null, 0, null, null, 0, open, closesAt, previous);
        return new Prediction(current.day(), current.stockCode(), current.basePrice(), current.closePrice(), current.result(),
                current.reward(), false, closesAt, previous);
    }

    /** Settles every finished day once a minute; also run for one member when they look. */
    @Scheduled(fixedDelayString = "${gamestock.engagement.settle-ms:60000}", initialDelayString = "${gamestock.engagement.settle-initial-ms:90000}")
    public void settleTick() {
        try {
            settlePending(null);
        } catch (RuntimeException error) {
            log.warn("일일 예측 정산에 실패했습니다. 다음 주기에 다시 시도합니다.", error);
        }
    }

    void settlePending(Long userId) {
        LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
        var pending = userId == null
                ? jdbc.queryForList("SELECT user_id, day, stock_id, base_price FROM daily_predictions WHERE result IS NULL AND day < ? LIMIT 500", Date.valueOf(today))
                : jdbc.queryForList("SELECT user_id, day, stock_id, base_price FROM daily_predictions WHERE result IS NULL AND day < ? AND user_id=?", Date.valueOf(today), userId);
        for (var row : pending) {
            long user = ((Number) row.get("user_id")).longValue();
            LocalDate day = ((Date) row.get("day")).toLocalDate();
            long stock = ((Number) row.get("stock_id")).longValue();
            long base = ((Number) row.get("base_price")).longValue();
            Timestamp end = Timestamp.from(day.plusDays(1).atStartOfDay(ZONE).toInstant());
            var closes = jdbc.queryForList("SELECT price FROM stock_price_history WHERE stock_id=? AND recorded_at<? ORDER BY recorded_at DESC LIMIT 1", Long.class, stock, end);
            long close = closes.isEmpty() ? jdbc.queryForObject("SELECT current_price FROM stocks WHERE id=?", Long.class, stock) : closes.get(0);
            boolean hit = close > base;
            transactions.executeWithoutResult(status -> {
                int claimed = jdbc.update("UPDATE daily_predictions SET result=?, close_price=?, reward_cash=? WHERE user_id=? AND day=? AND result IS NULL",
                        hit ? "HIT" : "MISS", close, hit ? PREDICTION_REWARD : 0, user, Date.valueOf(day));
                if (claimed == 1 && hit) pay(user, "prediction:" + user + ":" + day, PREDICTION_REWARD, "일일 예측 적중 " + day);
            });
        }
    }

    // ── 재기 지원금 ──────────────────────────────────────────

    private Rescue rescue(long userId) {
        Long net = jdbc.queryForObject("SELECT " + NET_ASSET + " FROM users u WHERE u.id=?", Long.class, userId);
        long netAsset = net == null ? 0 : net;
        Instant next = lastRescue(userId).map(at -> at.plus(RESCUE_COOLDOWN)).orElse(null);
        boolean cooled = next == null || !next.isAfter(clock.instant());
        return new Rescue(netAsset < RESCUE_LIMIT && cooled, RESCUE_AMOUNT, netAsset, RESCUE_LIMIT,
                cooled ? null : next.toString());
    }

    public Me claimRescue(long userId) {
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId);
            Rescue rescue = rescue(userId);
            if (rescue.netAsset() >= RESCUE_LIMIT)
                throw new IllegalArgumentException("순자산이 " + String.format(Locale.ROOT, "%,d", RESCUE_LIMIT) + "원 아래일 때만 받을 수 있어요.");
            if (!rescue.available()) throw new IllegalArgumentException("재기 지원금은 일주일에 한 번 받을 수 있어요.");
            long epochDay = clock.instant().atZone(ZONE).toLocalDate().toEpochDay();
            pay(userId, "rescue:" + userId + ":" + epochDay, RESCUE_AMOUNT, "재기 지원금");
        });
        return me(userId);
    }

    private java.util.Optional<Instant> lastRescue(long userId) {
        var rows = jdbc.queryForList("SELECT MAX(created_at) FROM admin_cash_adjustments WHERE user_id=? AND request_key LIKE 'rescue:%'", Timestamp.class, userId);
        return rows.isEmpty() || rows.get(0) == null ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0).toInstant());
    }

    // ── helpers ──────────────────────────────────────────────

    /** Same path as promotion codes: cash plus an admin cash adjustment that returns leave out. */
    private void pay(long userId, String key, long amount, String reason) {
        int recorded = jdbc.update("INSERT IGNORE INTO admin_cash_adjustments (request_key, admin_id, user_id, amount, reason) VALUES (?, 0, ?, ?, ?)",
                key, userId, amount, reason);
        if (recorded == 1) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", amount, userId);
    }

    private void ensureRow(long userId) {
        jdbc.update("INSERT IGNORE INTO user_engagement (user_id) VALUES (?)", userId);
    }

    private long stockId(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        var ids = jdbc.queryForList("SELECT id FROM stocks WHERE stock_code=?", Long.class, normalized);
        if (ids.isEmpty()) throw new IllegalArgumentException("종목을 찾을 수 없어요.");
        return ids.get(0);
    }

    private Timestamp now() { return Timestamp.from(clock.instant()); }

    private static double percent(long gain, long base) {
        return base <= 0 ? 0 : Math.round(gain * 10000.0 / base) / 100.0;
    }

    private static String iso(Object value) {
        return value instanceof Timestamp t ? t.toInstant().toString() : null;
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }
}
