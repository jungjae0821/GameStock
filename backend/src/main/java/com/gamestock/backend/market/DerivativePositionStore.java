package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 파생상품 포지션·체결 테이블의 스키마와 저장 도우미.
 *
 * <p>DerivativeService가 직접 생성하는 package-private 협력 객체다. 같은 JdbcTemplate을 쓰므로
 * 호출한 서비스의 트랜잭션에 그대로 참여하고, 행 잠금(FOR UPDATE/FOR SHARE) 순서는 호출자가 정한다.
 */
final class DerivativePositionStore {
    record OpenRow(long id, long userId, String kind, String symbol, String name, String side,
                   int quantity, double entryPrice, long entryNotional, long margin, int leverage,
                   String optionType, double strike, Timestamp expiryAt, String openedAt,
                   long realizedPnl) { }

    record PositionRow(long id, long userId, String kind, String symbol, String name, String side,
                       int quantity, double entryPrice, long entryNotional, long margin, int leverage,
                       String optionType, double strike, Timestamp expiryAt, String status,
                       String openedAt, long realizedPnl, double markPrice, long markValue) { }

    static final RowMapper<OpenRow> OPEN_ROW = (rs, row) -> new OpenRow(rs.getLong("id"), rs.getLong("user_id"),
            rs.getString("kind"), rs.getString("symbol"), rs.getString("name"), rs.getString("side"),
            rs.getInt("quantity"), rs.getDouble("entry_price"), rs.getLong("entry_notional"),
            rs.getLong("margin"), rs.getInt("leverage"), rs.getString("option_type"),
            rs.getDouble("strike"), rs.getTimestamp("expiry_at"),
            instant(rs.getTimestamp("opened_at")), rs.getLong("realized_pnl"));

    static final RowMapper<PositionRow> POSITION_ROW = (rs, row) -> new PositionRow(rs.getLong("id"), rs.getLong("user_id"),
            rs.getString("kind"), rs.getString("symbol"), rs.getString("name"), rs.getString("side"),
            rs.getInt("quantity"), rs.getDouble("entry_price"), rs.getLong("entry_notional"),
            rs.getLong("margin"), rs.getInt("leverage"), rs.getString("option_type"),
            rs.getDouble("strike"), rs.getTimestamp("expiry_at"), rs.getString("status"),
            instant(rs.getTimestamp("opened_at")), rs.getLong("realized_pnl"),
            rs.getDouble("mark_price"), rs.getLong("mark_value"));

    private final JdbcTemplate jdbc;
    private final Supplier<Clock> clock;

    DerivativePositionStore(JdbcTemplate jdbc, Supplier<Clock> clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 스키마 마이그레이션에서도 호출한다. 여러 번 실행해도 안전하다. */
    static void ensureTables(JdbcTemplate jdbc) {
        AdminSchema.ensureRuntime(jdbc);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_positions (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  kind VARCHAR(12) NOT NULL,
                  symbol VARCHAR(40) NOT NULL,
                  name VARCHAR(80) NOT NULL,
                  side VARCHAR(8) NOT NULL,
                  quantity INT NOT NULL,
                  entry_price DOUBLE NOT NULL,
                  entry_notional BIGINT NOT NULL,
                  margin BIGINT NOT NULL DEFAULT 0,
                  leverage INT NOT NULL DEFAULT 1,
                  option_type VARCHAR(4) NULL,
                  strike DOUBLE NULL,
                  expiry_at TIMESTAMP(3) NULL,
                  status VARCHAR(12) NOT NULL DEFAULT 'OPEN',
                  mark_price DOUBLE NOT NULL DEFAULT 0,
                  mark_value BIGINT NOT NULL DEFAULT 0,
                  mark_version BIGINT NOT NULL DEFAULT 0,
                  realized_pnl BIGINT NOT NULL DEFAULT 0,
                  opened_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  closed_at TIMESTAMP(3) NULL,
                  close_reason VARCHAR(16) NULL,
                  INDEX ix_derivative_user (user_id, status),
                  INDEX ix_derivative_expiry (status, expiry_at),
                  CONSTRAINT fk_derivative_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='derivative_positions' AND column_name='mark_version'", Integer.class) == 0)
            jdbc.execute("ALTER TABLE derivative_positions ADD COLUMN mark_version BIGINT NOT NULL DEFAULT 0");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_trades (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  position_id BIGINT NOT NULL,
                  action VARCHAR(12) NOT NULL,
                  kind VARCHAR(12) NOT NULL,
                  symbol VARCHAR(40) NOT NULL,
                  side VARCHAR(8) NOT NULL,
                  quantity INT NOT NULL,
                  price DOUBLE NOT NULL,
                  cash_flow BIGINT NOT NULL,
                  cash_flow_version TINYINT NOT NULL DEFAULT 2,
                  fee BIGINT NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  INDEX ix_derivative_trades_user (user_id, id),
                  CONSTRAINT fk_derivative_trade_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        Integer versionColumn = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='derivative_trades' AND column_name='cash_flow_version'", Integer.class);
        if (versionColumn == 0) jdbc.execute("ALTER TABLE derivative_trades ADD COLUMN cash_flow_version TINYINT NOT NULL DEFAULT 1");
        // Version guard makes the legacy ETF fee backfill safe to resume/re-run.
        jdbc.update("UPDATE derivative_trades SET cash_flow=cash_flow-fee,cash_flow_version=2 WHERE cash_flow_version=1 AND kind='ETF' AND action='OPEN'");
        jdbc.update("UPDATE derivative_trades SET cash_flow_version=2 WHERE cash_flow_version=1");
        String versionDefault=jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='derivative_trades' AND column_name='cash_flow_version'",String.class);
        if(!"2".equals(versionDefault))jdbc.execute("ALTER TABLE derivative_trades ALTER COLUMN cash_flow_version SET DEFAULT 2");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='derivative_trades' AND index_name='ix_derivative_trades_flow'", Integer.class) == 0)
            jdbc.execute("ALTER TABLE derivative_trades ADD INDEX ix_derivative_trades_flow (kind, created_at)");
        DerivativeIndexHistory.ensureTables(jdbc);
        DerivativePriceOracle.ensureTables(jdbc);
        DerivativeBaskets.ensureTables(jdbc);
        // Freeze legacy references during the locked migration, before startup rolls the day.
        new DerivativeIndexHistory(jdbc).initializeBases();
        new DerivativePriceOracle(jdbc).initialize();
        new DerivativeBaskets(jdbc).rebalance();
    }

    // ------------------------------------------------------------ 조회·잠금

    void requireTradingEnabled() {
        // Current shared read held until commit: an admin pause waits for admitted trades,
        // while concurrent orders remain independent. Acquire before any user/position lock.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT market_paused FROM admin_runtime WHERE id=1 FOR SHARE", Boolean.class)))
            throw new IllegalArgumentException("운영 점검 중에는 파생상품 주문과 청산이 중단됩니다.");
    }

    void lockUser(long userId) {
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
    }

    List<PositionRow> positionRows(long userId) {
        return jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, status, opened_at, realized_pnl, mark_price, mark_value
                FROM derivative_positions WHERE user_id = ? AND status = 'OPEN' ORDER BY id DESC
                """, POSITION_ROW, userId);
    }

    List<PositionRow> openEtfRows(long userId, String code) {
        return jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, status, opened_at, realized_pnl, mark_price, mark_value
                FROM derivative_positions
                WHERE user_id = ? AND kind = 'ETF' AND symbol = ? AND status = 'OPEN'
                ORDER BY id
                """, POSITION_ROW, userId, code);
    }

    PositionRow lockPosition(long userId, long positionId) {
        List<PositionRow> rows = jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, status, opened_at, realized_pnl, mark_price, mark_value
                FROM derivative_positions WHERE id = ? AND user_id = ? FOR UPDATE
                """, POSITION_ROW, positionId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    OpenRow findOpenPosition(long positionId) {
        List<OpenRow> rows = jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, opened_at, realized_pnl
                FROM derivative_positions WHERE id = ? AND status = 'OPEN'
                """, OPEN_ROW, positionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    OpenRow lockOpenPosition(long positionId) {
        List<OpenRow> rows = jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, opened_at, realized_pnl
                FROM derivative_positions WHERE id = ? AND status = 'OPEN' FOR UPDATE
                """, OPEN_ROW, positionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------ 기록

    void debit(long userId, long amount, long fee, String label) {
        long required = amount + fee;
        Long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        if (cash == null || cash < required)
            throw new IllegalArgumentException(label + "이(가) 부족합니다. 필요 " + won(required)
                    + " · 보유 " + won(cash == null ? 0 : cash));
        jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ?", required, userId);
    }

    long insertPosition(long userId, String kind, String symbol, String name, String side,
                        int quantity, double entryPrice, long entryNotional, long margin,
                        int leverage, String optionType, double strike, Timestamp expiryAt,
                        long markValue, long markVersion) {
        jdbc.update("""
                INSERT INTO derivative_positions
                  (user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin, leverage,
                   option_type, strike, expiry_at, status, mark_price, mark_value, mark_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
                """, userId, kind, symbol, name, side, quantity, entryPrice, entryNotional, margin, leverage,
                optionType, strike, expiryAt, entryPrice, markValue, markVersion);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    void recordTrade(long userId, long positionId, String action, String kind, String symbol,
                     String side, int quantity, double price, long cashFlow, long fee) {
        jdbc.update("""
                INSERT INTO derivative_trades
                  (user_id, position_id, action, kind, symbol, side, quantity, price, cash_flow, fee, cash_flow_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 2, ?)
                """, userId, positionId, action, kind, symbol, side, quantity, price, cashFlow, fee, Timestamp.from(clock.get().instant()));
    }

    static String instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    static String won(long value) {
        return String.format(Locale.KOREA, "%,d원", value);
    }
}
