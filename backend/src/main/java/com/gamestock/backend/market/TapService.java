package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 탭 베팅. 차트 오른쪽 격자의 칸(미래 5초 구간 × 가격 구간)에 모의 자금을 걸고,
 * 그 구간 동안 실제 체결가가 칸의 가격대를 지나면 건 금액 × 배당을 받는다.
 *
 * <ul>
 *   <li>대형주 8종목의 실제 체결가로만 판정한다. 가격은 매칭이 만든 값 그대로다.</li>
 *   <li>건 금액은 걸 때 현금에서 빠지고, 당첨되면 배당을 곱한 금액이 현금으로 들어온다.</li>
 *   <li>배당은 서버가 판정 시세의 변동성으로 계산한다(TapOdds). 하우스 몫 8%.</li>
 *   <li>칸에 닿는 순간 당첨이고, 구간이 끝날 때까지 닿지 않으면 낙첨이다.</li>
 *   <li>서버가 다시 시작되는 등 구간을 끝까지 지켜보지 못하면 건 금액을 돌려준다.</li>
 * </ul>
 * 판정은 체결가를 직접 받는 매칭 소유 인스턴스에서만 한다.
 */
@Service
public class TapService {
    private static final Logger log = LoggerFactory.getLogger(TapService.class);
    static final Set<String> CODES = Set.of("GI", "SR", "OW", "ZZZ", "WH", "GOV", "BA", "UMA");
    static final List<Long> STAKES = List.of(10_000L, 50_000L, 100_000L);
    static final long SLOT_MS = TapPriceTape.SLOT_MS;
    /** 칸이 열리기 최소 이만큼 전에 걸어야 한다. 화면 시세 지연으로 미래를 엿보지 못하게 한다. */
    static final long MIN_LEAD_MS = 1_500;
    static final int MAX_COLUMNS = 8;
    static final int MAX_OPEN_BETS = 24;
    /** 마지막 체결이 늦게 커밋돼도 낙첨으로 먼저 정하지 않도록 구간 끝 뒤 잠시 기다린다. */
    static final long SETTLE_GRACE_MS = 400;
    /** 구간이 끝나고 이만큼 지나도 판정할 시세가 없으면 환불한다. */
    static final long VOID_AFTER_MS = 30_000;
    /** 화면 배당보다 서버 배당이 이 비율 넘게 낮으면 다시 누르게 한다. */
    static final double QUOTE_TOLERANCE = 0.9;
    private static final int PLACES_PER_MINUTE = 120;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String BOT_FILTER = "password_hash NOT IN ('BOT', 'TRADER')";

    public record PlaceRequest(String code, Long slotStart, Long bandLow, Long bandHigh, Long stake, Double quotedMultiplier) { }
    public record TapBet(long id, String code, long slotStart, long slotEnd, long bandLow, long bandHigh, long stake,
                         double multiplier, String status, long payout, String createdAt, String settledAt) { }
    public record TapStats(long staked, long paid, int wins, int losses) { }
    public record TapState(String code, boolean ready, long serverTime, long price, double sigma, long band, long previousBand,
                           long tick, long slotMs, long minLeadMs, int maxColumns, double houseEdge, double minMultiplier,
                           double maxMultiplier, List<Long> stakes, List<String> codes, List<long[]> path, double[][][] probabilities, long recentLow, long recentHigh, long recentMs, long leadFromMs, long leadStepMs,
                           List<TapBet> bets, TapStats today) { }
    public record PlaceResult(String message, TapBet bet, MarketModels.Portfolio portfolio) { }

    private record OpenBet(long id, long userId, String code, long slotStart, long slotEnd, long bandLow, long bandHigh,
                           long stake, int multiplierMilli) { }

    private final JdbcTemplate jdbc;
    private final MarketService market;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final TapPriceTape tape = new TapPriceTape();
    private final Map<Long, Deque<Long>> recentPlaces = new ConcurrentHashMap<>();
    private volatile boolean tracking;
    private Clock clock = Clock.systemUTC();

    public TapService(JdbcTemplate jdbc, MarketService market, ApplicationEventPublisher events, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.market = market;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
    }

    void setClock(Clock clock) { this.clock = clock; }
    TapPriceTape tape() { return tape; }

    static void ensureTables(JdbcTemplate db) {
        db.execute("""
                CREATE TABLE IF NOT EXISTS tap_bets (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, stock_code VARCHAR(12) NOT NULL,
                  slot_start BIGINT NOT NULL, slot_end BIGINT NOT NULL, band_low BIGINT NOT NULL, band_high BIGINT NOT NULL,
                  stake BIGINT NOT NULL, multiplier_milli INT NOT NULL, status VARCHAR(8) NOT NULL DEFAULT 'OPEN',
                  payout BIGINT NOT NULL DEFAULT 0,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), settled_at TIMESTAMP(3) NULL,
                  UNIQUE KEY ux_tap_cell (user_id, stock_code, slot_start, band_low),
                  KEY ix_tap_open (status, slot_start), KEY ix_tap_user (user_id, id),
                  CONSTRAINT fk_tap_bets_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
    }

    /** 매칭이 커밋한 체결가. 이 인스턴스가 매칭 소유자일 때만 종목 값이 들어 있다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void marketChanged(MarketChangedEvent event) {
        if (!tracking || event.symbols().isEmpty()) return;
        long now = clock.millis();
        for (String code : CODES) {
            if (event.symbols().get(code) instanceof Map<?, ?> update && update.get("stock") instanceof MarketModels.Stock stock)
                tape.observe(code, stock.price(), now);
        }
    }

    @Scheduled(fixedDelayString = "${gamestock.tap.tick-ms:250}", initialDelayString = "${gamestock.tap.initial-delay-ms:5000}")
    public void tick() {
        try {
            if (!market.ownerAvailable()) {
                if (tracking) { tracking = false; tape.stop(); }
                return;
            }
            long now = clock.millis();
            if (!tracking) startTracking(now);
            tape.advance(now);
            settle(now);
        } catch (RuntimeException error) {
            log.warn("탭 베팅 정산에 실패했습니다. 다음 주기에 다시 시도합니다.", error);
        }
    }

    private void startTracking(long now) {
        Map<String, Long> prices = new HashMap<>();
        jdbc.query("SELECT stock_code, current_price FROM stocks WHERE stock_code IN ('GI','SR','OW','ZZZ','WH','GOV','BA','UMA')",
                rs -> { prices.put(rs.getString(1), rs.getLong(2)); });
        prices.forEach((code, price) -> tape.start(code, price, now));
        tracking = true;
        log.info("탭 베팅 시세 기록을 시작했습니다 ({}종목).", prices.size());
    }

    public TapState state(String code, Long userId) {
        String symbol = validCode(code);
        long now = clock.millis();
        TapPriceTape.Snapshot snapshot = tracking ? tape.snapshot(symbol) : null;
        List<TapBet> bets = userId == null ? List.of() : jdbc.query("""
                SELECT * FROM tap_bets WHERE user_id = ? AND stock_code = ?
                  AND (status = 'OPEN' OR settled_at >= ?) ORDER BY id DESC LIMIT 60
                """, (rs, row) -> bet(rs), userId, symbol, Timestamp.from(Instant.ofEpochMilli(now - 180_000)));
        TapStats today = userId == null ? new TapStats(0, 0, 0, 0) : jdbc.query("""
                SELECT COALESCE(SUM(stake),0), COALESCE(SUM(payout),0),
                       COALESCE(SUM(status='WON'),0), COALESCE(SUM(status='LOST'),0)
                FROM tap_bets WHERE user_id = ? AND created_at >= ? AND status <> 'VOID'
                """, (rs, row) -> new TapStats(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4)),
                userId, Timestamp.from(LocalDate.now(clock.withZone(ZONE)).atStartOfDay(ZONE).toInstant())).get(0);
        List<String> codes = List.of("GI", "SR", "OW", "ZZZ", "WH", "GOV", "BA", "UMA");
        if (snapshot == null)
            return new TapState(symbol, false, now, 0, 0, 0, 0, 0, SLOT_MS, MIN_LEAD_MS, MAX_COLUMNS, TapOdds.HOUSE_EDGE,
                    TapOdds.MIN_MULTIPLIER, TapOdds.MAX_MULTIPLIER, STAKES, codes, List.of(), new double[0][0][0], 0, 0, TapCalibration.RECENT_MS,
                    TapCalibration.LEAD_FROM, TapCalibration.LEAD_STEP, bets, today);
        return new TapState(symbol, true, now, snapshot.price(), snapshot.sigmaPerSqrtSecond(), snapshot.band(), snapshot.previousBand(),
                snapshot.tick(), SLOT_MS, MIN_LEAD_MS, MAX_COLUMNS, TapOdds.HOUSE_EDGE, TapOdds.MIN_MULTIPLIER,
                TapOdds.MAX_MULTIPLIER, STAKES, codes, snapshot.path(), snapshot.probabilities(),
                snapshot.recentLow(), snapshot.recentHigh(), TapCalibration.RECENT_MS,
                TapCalibration.LEAD_FROM, TapCalibration.LEAD_STEP, bets, today);
    }

    public PlaceResult place(long userId, PlaceRequest request) {
        if (request == null) throw new IllegalArgumentException("걸 칸을 골라 주세요.");
        String code = validCode(request.code());
        long stake = request.stake() == null ? 0 : request.stake();
        if (!STAKES.contains(stake)) throw new IllegalArgumentException("건 금액은 1만·5만·10만원 중에서 골라 주세요.");
        if (request.slotStart() == null || request.bandLow() == null || request.bandHigh() == null)
            throw new IllegalArgumentException("걸 칸을 골라 주세요.");
        long slotStart = request.slotStart(), low = request.bandLow(), high = request.bandHigh();
        long now = clock.millis();
        TapPriceTape.Snapshot snapshot = tracking ? tape.snapshot(code) : null;
        if (snapshot == null) throw new IllegalStateException("탭 베팅 시세를 준비하고 있어요. 잠시 후 다시 눌러 주세요.");
        if (slotStart % SLOT_MS != 0) throw new IllegalArgumentException("칸 시간이 올바르지 않아요.");
        if (slotStart - now < MIN_LEAD_MS) throw new IllegalArgumentException("이미 지나갔거나 곧 열리는 칸이에요. 더 오른쪽 칸을 눌러 주세요.");
        if (slotStart > now + MAX_COLUMNS * SLOT_MS) throw new IllegalArgumentException("너무 먼 칸이에요.");
        long band = high - low;
        if (low <= 0 || (band != snapshot.band() && band != snapshot.previousBand()) || low % band != 0)
            throw new IllegalArgumentException("칸 가격대가 바뀌었어요. 화면을 새로 고친 뒤 다시 눌러 주세요.");
        // 체결이 뜸하면 실제 변동성이 바닥값보다 작아 현재가 칸이 거의 확실히 닿는다. 배당 모델과 상관없이 막는다.
        if (snapshot.price() >= low && snapshot.price() < high)
            throw new IllegalArgumentException("지금 가격이 들어 있는 칸은 걸 수 없어요.");
        // 칸 높이(1틱) 단위로 지금 가격에서 몇 칸 떨어졌는지. 현재가 칸은 위에서 이미 막았다.
        int distance = (int) (snapshot.price() < low ? -Math.floorDiv(snapshot.price() - low, band) : -Math.floorDiv(low - snapshot.price(), band));
        // 목표 가격: 칸 안에서 지금 가격에 가장 가까운 체결 가능 가격.
        long target = snapshot.price() < low ? low : high - band;
        double probability = tape.probability(code, Math.max(1, distance), slotStart - now, target);
        double offered = TapOdds.multiplier(probability);
        if (offered <= 0) throw new IllegalArgumentException(probability > 0.5
                ? "지금 가격과 너무 가까워 걸 수 없는 칸이에요." : "지금 가격에서 너무 멀어 걸 수 없는 칸이에요.");
        Double quoted = request.quotedMultiplier();
        if (quoted != null && Double.isFinite(quoted) && quoted > 0 && offered < quoted * QUOTE_TOLERANCE)
            throw new IllegalArgumentException(String.format("배당이 %.1f×로 바뀌었어요. 다시 눌러 주세요.", offered));
        double multiplier = quoted != null && Double.isFinite(quoted) && quoted >= TapOdds.MIN_MULTIPLIER ? Math.min(offered, quoted) : offered;
        int milli = (int) Math.round(Math.floor(multiplier * 10 + 1e-9) * 100);
        throttle(userId, now);
        long id = transaction.execute(state -> {
            Map<String, Object> account = jdbc.queryForMap("SELECT cash, (" + BOT_FILTER + ") AS human FROM users WHERE id = ? FOR UPDATE", userId);
            if (!Boolean.TRUE.equals(toBoolean(account.get("human")))) throw new IllegalArgumentException("탭 베팅은 회원 계정에서만 할 수 있어요.");
            int open = jdbc.queryForObject("SELECT COUNT(*) FROM tap_bets WHERE user_id = ? AND status = 'OPEN'", Integer.class, userId);
            if (open >= MAX_OPEN_BETS) throw new IllegalArgumentException("결과를 기다리는 칸이 너무 많아요. 몇 칸이 끝난 뒤 다시 걸어 주세요.");
            if (jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ? AND cash >= ?", stake, userId, stake) == 0)
                throw new IllegalArgumentException("현금이 부족해요.");
            try {
                jdbc.update("""
                        INSERT INTO tap_bets (user_id, stock_code, slot_start, slot_end, band_low, band_high, stake, multiplier_milli)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, userId, code, slotStart, slotStart + SLOT_MS, low, high, stake, milli);
            } catch (DuplicateKeyException duplicate) {
                throw new IllegalArgumentException("이미 건 칸이에요.");
            }
            return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        });
        TapBet bet = jdbc.queryForObject("SELECT * FROM tap_bets WHERE id = ?", (rs, row) -> bet(rs), id);
        return new PlaceResult(String.format("%s × %.1f 걸었어요.", won(stake), milli / 1000.0), bet, market.portfolio(userId));
    }

    /** 닿은 칸은 바로 당첨, 끝난 칸은 낙첨, 지켜보지 못한 칸은 환불한다. */
    void settle(long now) {
        List<OpenBet> open = jdbc.query("""
                SELECT id, user_id, stock_code, slot_start, slot_end, band_low, band_high, stake, multiplier_milli
                FROM tap_bets WHERE status = 'OPEN' AND slot_start <= ? ORDER BY slot_start, id LIMIT 500
                """, (rs, row) -> new OpenBet(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getInt(9)), now);
        if (open.isEmpty()) return;
        Map<Long, List<TapBet>> settledByUser = new LinkedHashMap<>();
        for (OpenBet bet : open) {
            String outcome = outcome(bet, now);
            if (outcome == null) continue;
            long payout = switch (outcome) {
                case "WON" -> Math.floorDiv(bet.stake() * bet.multiplierMilli(), 1000);
                case "VOID" -> bet.stake();
                default -> 0;
            };
            Boolean applied = transaction.execute(state -> {
                if (jdbc.update("UPDATE tap_bets SET status = ?, payout = ?, settled_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND status = 'OPEN'",
                        outcome, payout, bet.id()) == 0) return false;
                if (payout > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", payout, bet.userId());
                return true;
            });
            if (Boolean.TRUE.equals(applied)) {
                TapBet view = new TapBet(bet.id(), bet.code(), bet.slotStart(), bet.slotEnd(), bet.bandLow(), bet.bandHigh(), bet.stake(),
                        bet.multiplierMilli() / 1000.0, outcome, payout, null, Instant.ofEpochMilli(now).toString());
                settledByUser.computeIfAbsent(bet.userId(), id -> new ArrayList<>()).add(view);
            }
        }
        settledByUser.forEach((userId, bets) -> {
            try {
                events.publishEvent(new UserMarketEvent(userId, "TAP_SETTLED", Map.of("portfolio", market.portfolio(userId), "bets", bets)));
            } catch (RuntimeException error) {
                log.debug("탭 베팅 결과 알림을 보내지 못했습니다: {}", error.getMessage());
            }
        });
    }

    private String outcome(OpenBet bet, long now) {
        long[] range = tape.range(bet.code(), bet.slotStart() / SLOT_MS);
        if (range != null && range[1] >= bet.bandLow() && range[0] < bet.bandHigh()) return "WON";
        if (range != null && now >= bet.slotEnd() + SETTLE_GRACE_MS && tape.coveredUntil(bet.code(), bet.slotEnd())) return "LOST";
        if (now >= bet.slotEnd() + VOID_AFTER_MS) return "VOID";
        return null;
    }

    /** 결과가 난 지 2주가 지난 기록은 지운다. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void prune() {
        try {
            jdbc.update("DELETE FROM tap_bets WHERE status <> 'OPEN' AND created_at < ? LIMIT 20000",
                    Timestamp.from(Instant.ofEpochMilli(clock.millis()).minusSeconds(14 * 86_400)));
        } catch (RuntimeException error) {
            log.warn("오래된 탭 베팅 기록을 지우지 못했습니다.", error);
        }
        long now = clock.millis();
        recentPlaces.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) { return entry.getValue().isEmpty() || now - entry.getValue().peekLast() > 60_000; }
        });
    }

    private void throttle(long userId, long now) {
        Deque<Long> times = recentPlaces.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.pollFirst();
            if (times.size() >= PLACES_PER_MINUTE) throw new IllegalArgumentException("너무 빠르게 누르고 있어요. 잠시 후 다시 걸어 주세요.");
            times.addLast(now);
        }
    }

    private static String validCode(String code) {
        String symbol = code == null ? "" : code.trim().toUpperCase();
        if (!CODES.contains(symbol)) throw new IllegalArgumentException("탭 베팅은 대형주 8종목에서만 할 수 있어요.");
        return symbol;
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) return number.intValue() != 0;
        return null;
    }

    private static TapBet bet(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp settled = rs.getTimestamp("settled_at");
        return new TapBet(rs.getLong("id"), rs.getString("stock_code"), rs.getLong("slot_start"), rs.getLong("slot_end"),
                rs.getLong("band_low"), rs.getLong("band_high"), rs.getLong("stake"), rs.getInt("multiplier_milli") / 1000.0,
                rs.getString("status"), rs.getLong("payout"), rs.getTimestamp("created_at").toInstant().toString(),
                settled == null ? null : settled.toInstant().toString());
    }

    private static String won(long amount) { return String.format("%,d원", amount); }
}
