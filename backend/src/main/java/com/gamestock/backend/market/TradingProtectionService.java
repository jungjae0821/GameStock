package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static com.gamestock.backend.market.MarketModels.*;

/** Mutations run inside MarketService's transaction and market_locks row lock. */
@Service
public class TradingProtectionService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long VI_SECONDS = 120;
    private static final long CB_HALT_SECONDS = 1200;
    private static final long CB_AUCTION_SECONDS = 600;
    private final JdbcTemplate jdbc;

    public TradingProtectionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void ensureTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS market_protection_state (
                  id INT PRIMARY KEY, trading_date DATE NOT NULL,
                  phase VARCHAR(16) NOT NULL DEFAULT 'NORMAL', level INT NOT NULL DEFAULT 0,
                  trigger_index DOUBLE NOT NULL DEFAULT 1000,
                  below_since TIMESTAMP NULL, started_at TIMESTAMP NULL, ends_at TIMESTAMP NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS stock_protection_state (
                  stock_id BIGINT PRIMARY KEY, day_reference BIGINT NOT NULL,
                  static_reference BIGINT NOT NULL, dynamic_reference BIGINT NOT NULL,
                  last_trade_price BIGINT NOT NULL,
                  vi_type VARCHAR(24) NULL, started_at TIMESTAMP NULL, ends_at TIMESTAMP NULL,
                  CONSTRAINT fk_protection_stock FOREIGN KEY (stock_id) REFERENCES stocks(id) ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS trading_restriction_events (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_id BIGINT NULL,
                  kind VARCHAR(24) NOT NULL, level INT NOT NULL DEFAULT 0,
                  reference_price DOUBLE NOT NULL, trigger_price DOUBLE NOT NULL,
                  started_at TIMESTAMP NOT NULL, ends_at TIMESTAMP NOT NULL,
                  CONSTRAINT fk_restriction_event_stock FOREIGN KEY (stock_id) REFERENCES stocks(id) ON DELETE CASCADE
                )
                """);
        ensureDynamicReferenceColumn();
        refreshDay();
    }

    private void ensureDynamicReferenceColumn() {
        Integer columnCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'stock_protection_state' AND column_name = 'dynamic_reference'", Integer.class);
        if (columnCount != null && columnCount == 0) {
            jdbc.execute("ALTER TABLE stock_protection_state ADD COLUMN dynamic_reference BIGINT NULL AFTER static_reference");
        }
        Integer nullableCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'stock_protection_state' AND column_name = 'dynamic_reference' AND is_nullable = 'YES'", Integer.class);
        if (nullableCount != null && nullableCount > 0) {
            jdbc.update("UPDATE stock_protection_state SET dynamic_reference = last_trade_price WHERE dynamic_reference IS NULL");
            jdbc.execute("ALTER TABLE stock_protection_state MODIFY COLUMN dynamic_reference BIGINT NOT NULL");
        }
    }

    public void refreshDay() {
        LocalDate today = LocalDate.now(SEOUL);
        jdbc.update("INSERT IGNORE INTO market_protection_state (id, trading_date) VALUES (1, ?)", today);
        MarketState state = marketState();
        if (!today.equals(state.day())) {
            jdbc.update("""
                    UPDATE market_protection_state SET trading_date = ?, phase = 'NORMAL', level = 0,
                      trigger_index = 1000, below_since = NULL, started_at = NULL, ends_at = NULL WHERE id = 1
                    """, today);
            jdbc.update("""
                    UPDATE stock_protection_state p JOIN stocks s ON s.id = p.stock_id
                    SET p.day_reference = s.current_price, p.static_reference = s.current_price,
                        p.dynamic_reference = s.current_price, p.last_trade_price = s.current_price, p.vi_type = NULL,
                        p.started_at = NULL, p.ends_at = NULL
                    """);
        }
        jdbc.update("""
                INSERT IGNORE INTO stock_protection_state (stock_id, day_reference, static_reference, dynamic_reference, last_trade_price)
                SELECT id, current_price, current_price, current_price, current_price FROM stocks
                """);
    }

    public double indexValue() {
        Double index = jdbc.queryForObject("""
                SELECT COALESCE(AVG(s.current_price * 1000.0 / p.day_reference), 1000)
                FROM stocks s JOIN stock_protection_state p ON p.stock_id = s.id
                """, Double.class);
        return index == null ? 1000 : index;
    }

    public MarketStatus marketStatus() {
        MarketState state = marketState();
        TradingRestriction restriction = marketRestriction(state);
        boolean open = "NORMAL".equals(state.phase()) || "AUCTION".equals(state.phase());
        return new MarketStatus(open, "NORMAL".equals(state.phase()) ? "24H" : "CB_" + state.phase(),
                "Asia/Seoul", restriction == null ? "24시간 거래 가능" : restriction.effect(),
                restriction, indexValue(), state.day().toString());
    }

    public TradingRestriction restriction(String code) {
        TradingRestriction market = marketRestriction(marketState());
        if (market != null) return market;
        List<TradingRestriction> rows = jdbc.query("""
                SELECT p.* FROM stock_protection_state p JOIN stocks s ON s.id = p.stock_id
                WHERE s.stock_code = ? AND p.vi_type IS NOT NULL
                """, (rs, row) -> {
            String kind = rs.getString("vi_type");
            boolean dynamic = "DYNAMIC_VI".equals(kind);
            return new TradingRestriction(kind, dynamic ? "동적 VI" : "정적 VI", "AUCTION", 0,
                    dynamic ? "직전 체결가 또는 VI 재개 기준가 대비 예상 체결가가 ±6% 이상 변동했습니다."
                            : "당일 시작가 또는 직전 단일가 대비 가격이 ±10% 이상 변동했습니다.",
                    "2분간 즉시 체결을 멈추고 지정가 주문을 모아 하나의 가격으로 체결합니다. 지정가 접수·취소는 가능하며 시장가 주문은 제한됩니다.",
                    instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("ends_at")), true, false);
        }, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean continuous(String code) { return restriction(code) == null; }

    public void validateOrder(String code, String type) {
        TradingRestriction restriction = restriction(code);
        if (restriction == null) return;
        if (!restriction.limitOrdersAllowed()) throw new IllegalArgumentException(restriction.label() + " 발동 중: 신규 주문이 중단됐습니다. 기존 주문 취소는 가능합니다.");
        if ("MARKET".equals(type)) throw new IllegalArgumentException(restriction.label() + " 단일가 접수 중: 지정가 주문을 이용해 주세요.");
    }

    /** Evaluate the prospective fill before transferring cash or shares. */
    public boolean beforeTrade(String code, long price) {
        if (!continuous(code)) return false;
        return !triggerVi(code, price, null);
    }

    public void recordTrade(long stockId, long price) {
        jdbc.update("UPDATE stock_protection_state SET last_trade_price = ?, dynamic_reference = ? WHERE stock_id = ?",
                price, price, stockId);
    }

    /** Synthetic news moves also cool the stock; subsequent moves stay frozen. */
    public void observeSyntheticMove(String code, long previous, long next) {
        if (continuous(code)) triggerVi(code, next, previous);
    }

    private boolean triggerVi(String code, long price, Long syntheticReference) {
        List<long[]> refs = jdbc.query("""
                SELECT p.stock_id, p.static_reference, p.dynamic_reference
                FROM stock_protection_state p JOIN stocks s ON s.id = p.stock_id WHERE s.stock_code = ?
                """, (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, code);
        if (refs.isEmpty()) return false;
        long[] ref = refs.get(0);
        long dynamicReference = syntheticReference == null ? ref[2] : syntheticReference;
        boolean dynamic = Math.abs(price / (double) dynamicReference - 1) >= 0.06 - 1e-10;
        boolean fixed = Math.abs(price / (double) ref[1] - 1) >= 0.10 - 1e-10;
        if (!dynamic && !fixed) return false;
        String kind = dynamic ? "DYNAMIC_VI" : "STATIC_VI";
        Instant now = Instant.now();
        Instant until = now.plusSeconds(VI_SECONDS);
        jdbc.update("UPDATE stock_protection_state SET vi_type = ?, started_at = ?, ends_at = ? WHERE stock_id = ?",
                kind, Timestamp.from(now), Timestamp.from(until), ref[0]);
        logEvent(ref[0], kind, 0, dynamic ? dynamicReference : ref[1], price, now, until);
        return true;
    }

    /** Equal-weight virtual index, not a real exchange index or market-cap estimate. */
    public void observeMarket() {
        MarketState state = marketState();
        if (!"NORMAL".equals(state.phase()) || state.level() >= 3) return;
        double value = indexValue();
        int next = state.level() + 1;
        double threshold = switch (next) { case 1 -> 920; case 2 -> 850; default -> 800; };
        boolean crossed = value <= threshold + 1e-8
                && (next == 1 || value <= state.triggerIndex() * 0.99 + 1e-8);
        if (!crossed) {
            jdbc.update("UPDATE market_protection_state SET below_since = NULL WHERE id = 1");
            return;
        }
        Instant now = Instant.now();
        if (state.belowSince() == null) {
            jdbc.update("UPDATE market_protection_state SET below_since = ? WHERE id = 1", Timestamp.from(now));
            return;
        }
        if (now.isBefore(state.belowSince().plusSeconds(60))) return;
        Instant until = next == 3 ? state.day().plusDays(1).atStartOfDay(SEOUL).toInstant()
                : now.plusSeconds(CB_HALT_SECONDS);
        jdbc.update("""
                UPDATE market_protection_state SET phase = ?, level = ?, trigger_index = ?, below_since = NULL,
                  started_at = ?, ends_at = ? WHERE id = 1
                """, next == 3 ? "CLOSED" : "HALTED", next, value, Timestamp.from(now), Timestamp.from(until));
        // Market-wide protection takes precedence and cancels existing VI timers.
        jdbc.update("UPDATE stock_protection_state SET vi_type = NULL, started_at = NULL, ends_at = NULL");
        logEvent(null, "CIRCUIT_BREAKER", next, 1000, value, now, until);
    }

    public void advanceMarketPhase() {
        MarketState state = marketState();
        if ("HALTED".equals(state.phase()) && !Instant.now().isBefore(state.endsAt())) {
            jdbc.update("UPDATE market_protection_state SET phase = 'AUCTION', ends_at = ? WHERE id = 1",
                    Timestamp.from(state.endsAt().plusSeconds(CB_AUCTION_SECONDS)));
        }
    }

    public boolean marketAuctionDue() {
        MarketState state = marketState();
        return "AUCTION".equals(state.phase()) && !Instant.now().isBefore(state.endsAt());
    }

    public List<String> dueViAuctions() {
        if (!"NORMAL".equals(marketState().phase())) return List.of();
        return jdbc.queryForList("""
                SELECT s.stock_code FROM stock_protection_state p JOIN stocks s ON s.id = p.stock_id
                WHERE p.vi_type IS NOT NULL AND p.ends_at <= ? ORDER BY s.id
                """, String.class, Timestamp.from(Instant.now()));
    }

    public void finishAuction(String code, long price) {
        // The simulator resumes after two minutes even without a matching pair.
        // Anchor both VI checks to the reopening quote in that case, while keeping
        // the real last fill untouched. Otherwise the same old gap triggers VI forever.
        jdbc.update("""
                UPDATE stock_protection_state p JOIN stocks s ON s.id = p.stock_id
                SET p.static_reference = ?, p.dynamic_reference = ?, p.vi_type = NULL,
                    p.started_at = NULL, p.ends_at = NULL
                WHERE s.stock_code = ?
                """, price, price, code);
    }

    public void finishMarketAuction() {
        jdbc.update("UPDATE market_protection_state SET phase = 'NORMAL', started_at = NULL, ends_at = NULL WHERE id = 1");
    }

    private TradingRestriction marketRestriction(MarketState state) {
        if ("NORMAL".equals(state.phase())) return null;
        boolean auction = "AUCTION".equals(state.phase());
        String phase = auction ? "AUCTION" : state.phase();
        String effect = auction
                ? "시장 전체가 10분간 지정가 주문을 모아 단일가로 재개합니다. 즉시 체결·시장가 주문은 제한되며 주문 취소는 가능합니다."
                : state.level() == 3
                ? "모든 종목의 신규 주문·체결·자동 시세 변경을 한국시간 자정까지 중단합니다. 기존 주문 취소는 가능합니다."
                : "모든 종목의 신규 주문·체결·자동 시세 변경을 20분간 중단합니다. 기존 주문 취소는 가능하며 이후 10분 단일가 접수를 거쳐 재개합니다.";
        return new TradingRestriction("CIRCUIT_BREAKER", "서킷 " + state.level() + "단계", phase, state.level(),
                "당일 시작을 1,000으로 둔 전 종목 동일가중 가상 지수가 " + switch (state.level()) { case 1 -> "8%"; case 2 -> "15%"; default -> "20%"; }
                        + " 이상 하락한 상태로 1분 유지됐습니다." + (state.level() > 1 ? " 직전 단계 발동 지수보다 1% 이상 추가 하락했습니다." : ""),
                effect, instant(state.startedAt()), instant(state.endsAt()), auction, false);
    }

    private MarketState marketState() {
        return jdbc.queryForObject("SELECT * FROM market_protection_state WHERE id = 1", (rs, row) ->
                new MarketState(rs.getDate("trading_date").toLocalDate(), rs.getString("phase"), rs.getInt("level"),
                        rs.getDouble("trigger_index"), nullableInstant(rs.getTimestamp("below_since")),
                        nullableInstant(rs.getTimestamp("started_at")), nullableInstant(rs.getTimestamp("ends_at"))));
    }

    private void logEvent(Long stockId, String kind, int level, double reference, double price, Instant now, Instant until) {
        jdbc.update("""
                INSERT INTO trading_restriction_events (stock_id, kind, level, reference_price, trigger_price, started_at, ends_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, stockId, kind, level, reference, price, Timestamp.from(now), Timestamp.from(until));
    }

    private static Instant nullableInstant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private static String instant(Instant value) { return value == null ? null : value.toString(); }
    private record MarketState(LocalDate day, String phase, int level, double triggerIndex,
                               Instant belowSince, Instant startedAt, Instant endsAt) { }
}
