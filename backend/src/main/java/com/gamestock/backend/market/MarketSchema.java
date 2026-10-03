package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Startup schema changes, separated from data initialization.
 *
 * <p>MySQL commits implicitly on every CREATE/ALTER, so these steps can never
 * share a rollback with data writes. Each step is idempotent and checks the
 * live schema first, so a restart after a partial failure resumes safely.
 * Backfills that must follow a newly added column stay next to that column.
 */
final class MarketSchema {
    private static final Logger log = LoggerFactory.getLogger(MarketSchema.class);
    private final JdbcTemplate jdbc;

    MarketSchema(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Runs every DDL step in the order MarketService.initializeData used before the split. */
    void migrate(UserFeatureService userFeatures, TradingProtectionService protection, PriceMetricService priceMetrics) {
        ensurePriceHistoryTable();
        ensureAuthenticationTables();
        ensureMarketEventColumns();
        ensureTradeTable();
        ensurePortfolioColumns();
        ensureSettlementTable();
        ensureDailySummaryTable();
        ensureSimulationState();
        ensureMarketLock();
        MarketBookCache.ensureTables(jdbc);
        BotLedgerJournal.ensureTables(jdbc);
        userFeatures.ensureTables();
        protection.ensureTables();
        ensureSimulationBookTables();
        priceMetrics.ensureTables();
        ensureOrderTimestampPrecision();
        ensureNewsPriceState();
    }

    private void ensureOrderTimestampPrecision() {
        for(String column:List.of("created_at","expires_at")) {
            Integer precision=jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='orders' AND column_name=?",Integer.class,column);
            if(precision==null || precision<3)jdbc.execute("ALTER TABLE orders MODIFY COLUMN "+column+" TIMESTAMP(3) "+(column.equals("created_at")?"NOT NULL DEFAULT CURRENT_TIMESTAMP(3)":"NULL"));
        }
    }

    private void ensureSimulationBookTables() {
        ensureOrderIndex("ix_orders_live_book", "stock_id,status,side,price,created_at,id");
        ensureOrderIndex("ix_orders_expiry", "status,expires_at");
        ensureOrderIndex("ix_orders_user_live", "user_id,status,stock_id");
        // Batch book loads scan OPEN rows in id order, while expiry cleanup
        // filters one stock by its deadline. Keep both paths selective as the
        // Railway order table grows.
        ensureOrderIndex("ix_orders_open_sequence", "status,id");
        ensureOrderIndex("ix_orders_stock_expiry", "stock_id,status,expires_at,id");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS market_price_metrics (
                    stock_id BIGINT PRIMARY KEY, mark_price BIGINT NOT NULL,
                    hidden_fundamental DOUBLE NOT NULL, anchor_price BIGINT NOT NULL,
                    last_news_id BIGINT NOT NULL DEFAULT 0, mark_updated_at TIMESTAMP(3) NULL)
                """);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='market_price_metrics' AND column_name='mark_updated_at'",Integer.class)==0)
            jdbc.execute("ALTER TABLE market_price_metrics ADD COLUMN mark_updated_at TIMESTAMP(3) NULL");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS lp_risk_books (
                    stock_id BIGINT PRIMARY KEY, opening_cash BIGINT NOT NULL,
                    baseline_trade_id BIGINT NOT NULL, target_inventory INT NOT NULL,
                    max_inventory INT NOT NULL, risk_limit BIGINT NOT NULL)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS lp_cash_projection (stock_id BIGINT PRIMARY KEY,last_id BIGINT NOT NULL,cash BIGINT NOT NULL)");
    }

    private void ensurePriceHistoryTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS stock_price_history (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  stock_id BIGINT NOT NULL,
                  price BIGINT NOT NULL,
                  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT fk_price_history_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_price_history_stock_time (stock_id, recorded_at)
                )
                """);
    }

    private void ensureAuthenticationTables() {
        addOrderColumnIfMissing();
        addUserColumnIfMissing("google_uid", "VARCHAR(128) NULL UNIQUE");
        addUserColumnIfMissing("email", "VARCHAR(255) NULL");
        addUserColumnIfMissing("profile_image_url", "VARCHAR(500) NULL");
        addUserColumnIfMissing("profile_completed", "BOOLEAN NOT NULL DEFAULT FALSE");
        addUserColumnIfMissing("role", "VARCHAR(20) NOT NULL DEFAULT 'USER'");
        addUserColumnIfMissing("account_reset_at", "TIMESTAMP NULL");
        addUserColumnIfMissing("reset_used_at", "TIMESTAMP NULL");
        addUserColumnIfMissing("nickname_changed_at", "TIMESTAMP NULL");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attendance_rewards (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, rewarded_on DATE NOT NULL,
                  streak_day INT NOT NULL, reward_cash BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uq_attendance_user_day (user_id, rewarded_on),
                  CONSTRAINT fk_attendance_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mission_rewards (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
                  mission_id VARCHAR(40) NOT NULL, rewarded_on DATE NOT NULL, reward_cash BIGINT NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uq_mission_reward_user_day (user_id, mission_id, rewarded_on),
                  CONSTRAINT fk_mission_reward_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        migrateDailyMissionRewards();
    }

    private void migrateDailyMissionRewards() {
        Integer columnCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mission_rewards' AND column_name = 'rewarded_on'", Integer.class);
        if (columnCount != null && columnCount == 0) {
            jdbc.execute("ALTER TABLE mission_rewards ADD COLUMN rewarded_on DATE NULL AFTER mission_id");
        }
        Integer nullableCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mission_rewards' AND column_name = 'rewarded_on' AND is_nullable = 'YES'", Integer.class);
        if (nullableCount != null && nullableCount > 0) {
            // JDBC sessions use UTC. Keep each legacy payout on its original Korean date.
            jdbc.update("UPDATE mission_rewards SET rewarded_on = DATE(CONVERT_TZ(created_at, '+00:00', '+09:00')) WHERE rewarded_on IS NULL");
            jdbc.execute("ALTER TABLE mission_rewards MODIFY COLUMN rewarded_on DATE NOT NULL");
        }
        Integer dailyIndexCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'mission_rewards' AND index_name = 'uq_mission_reward_user_day'", Integer.class);
        if (dailyIndexCount != null && dailyIndexCount == 0) {
            jdbc.execute("ALTER TABLE mission_rewards ADD UNIQUE KEY uq_mission_reward_user_day (user_id, mission_id, rewarded_on)");
        }
        Integer legacyIndexCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'mission_rewards' AND index_name = 'uq_mission_reward_user_mission'", Integer.class);
        if (legacyIndexCount != null && legacyIndexCount > 0) {
            // Add the daily index first so the user foreign key always has a supporting index.
            jdbc.execute("ALTER TABLE mission_rewards DROP INDEX uq_mission_reward_user_mission");
        }
    }

    private void addOrderColumnIfMissing() {
        boolean remainingQuantityAdded = addOrderColumnIfMissing("remaining_quantity", "INT NOT NULL DEFAULT 0");
        addOrderColumnIfMissing("reserved_cash", "BIGINT NOT NULL DEFAULT 0");
        addOrderColumnIfMissing("reserved_quantity", "INT NOT NULL DEFAULT 0");
        addOrderColumnIfMissing("expires_at", "TIMESTAMP NULL");
        if (remainingQuantityAdded) {
            // Only repair rows when migrating a legacy schema. The current schema already
            // initializes this column, so a table-wide UPDATE during startup would contend
            // with the live matching writer during a rolling Railway deployment.
            try {
                jdbc.update("""
                        UPDATE orders o
                        JOIN (
                          SELECT legacy.id FROM (
                            SELECT id FROM orders
                            WHERE remaining_quantity = 0 AND status = 'OPEN' AND quantity > 0
                            ORDER BY id LIMIT 500
                          ) legacy
                        ) candidates ON candidates.id = o.id
                        SET o.remaining_quantity = o.quantity
                        """);
            } catch (CannotAcquireLockException | QueryTimeoutException e) {
                log.warn("Skipping bounded legacy order quantity repair during startup: {}", e.getClass().getSimpleName());
            }
        }
        jdbc.execute("ALTER TABLE orders MODIFY COLUMN status ENUM('OPEN', 'FILLED', 'PARTIAL', 'CANCELLED') NOT NULL DEFAULT 'OPEN'");
    }

    private boolean addOrderColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) {
            jdbc.execute("ALTER TABLE orders ADD COLUMN " + name + " " + definition);
            return true;
        }
        return false;
    }

    private void addUserColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE users ADD COLUMN " + name + " " + definition);
    }

    private void ensureMarketEventColumns() {
        Integer sourceCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'market_events' AND column_name = 'source'", Integer.class);
        if (sourceCount != null && sourceCount == 0) jdbc.execute("ALTER TABLE market_events ADD COLUMN source VARCHAR(40) NOT NULL DEFAULT '미디어 보도' AFTER event_type");
        jdbc.update("UPDATE market_events SET source = '미디어 보도' WHERE source IS NULL OR source = ''");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'market_events' AND column_name = 'published_at'", Integer.class);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE market_events ADD COLUMN published_at TIMESTAMP NULL AFTER impact");
        Integer priceAtPublishCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'market_events' AND column_name = 'price_at_publish'", Integer.class);
        if (priceAtPublishCount != null && priceAtPublishCount == 0) jdbc.execute("ALTER TABLE market_events ADD COLUMN price_at_publish BIGINT NULL AFTER published_at");
        jdbc.update("UPDATE market_events SET published_at = created_at WHERE published_at IS NULL");
        // Existing events predate the persisted baseline. Reuse the closest
        // historical quote when available and fall back to the current quote
        // only for rows without enough history. Newly collected events receive
        // their collection-time price in NewsFeedService.
        jdbc.update("""
                UPDATE market_events e
                JOIN stocks s ON s.id = e.stock_id
                SET e.price_at_publish = COALESCE((
                    SELECT h.price FROM stock_price_history h
                    WHERE h.stock_id = e.stock_id
                      AND h.recorded_at <= COALESCE(e.published_at, e.created_at)
                    ORDER BY h.recorded_at DESC, h.id DESC LIMIT 1
                ), s.current_price)
                WHERE e.event_type = 'NEWS' AND e.price_at_publish IS NULL
                """);
        // News is read by stock and type repeatedly during feed rendering and
        // price-driver calculation. These indexes are also safe for an
        // existing Railway database because they are created only once.
        ensureIndex("market_events", "ix_market_events_stock_type_time",
                "stock_id,event_type,published_at,created_at,id");
        ensureIndex("market_events", "ix_market_events_stock_title", "stock_id,title");
    }

    private void ensureOrderIndex(String name,String columns) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='orders' AND index_name=?",Integer.class,name);
        if(count==null || count==0) jdbc.execute("CREATE INDEX "+name+" ON orders ("+columns+")");
    }

    private void ensureIndex(String table, String name, String columns) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=?",
                Integer.class, table, name);
        if (count == null || count == 0) jdbc.execute("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
    }

    private void ensureTradeTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS trades (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  stock_id BIGINT NOT NULL,
                  buy_order_id BIGINT NOT NULL,
                  sell_order_id BIGINT NOT NULL,
                  buyer_id BIGINT NOT NULL,
                  seller_id BIGINT NOT NULL,
                  maker_order_id BIGINT NOT NULL,
                  taker_order_id BIGINT NOT NULL,
                  aggressor_side ENUM('BUY', 'SELL') NOT NULL,
                  quantity INT NOT NULL,
                  price BIGINT NOT NULL,
                  buyer_fee BIGINT NOT NULL DEFAULT 0,
                  seller_fee BIGINT NOT NULL DEFAULT 0,
                  fee BIGINT NOT NULL DEFAULT 0,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT fk_trades_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  CONSTRAINT fk_trades_buy_order FOREIGN KEY (buy_order_id) REFERENCES orders(id),
                  CONSTRAINT fk_trades_sell_order FOREIGN KEY (sell_order_id) REFERENCES orders(id),
                  CONSTRAINT fk_trades_buyer FOREIGN KEY (buyer_id) REFERENCES users(id),
                  CONSTRAINT fk_trades_seller FOREIGN KEY (seller_id) REFERENCES users(id),
                  INDEX ix_trades_stock_time (stock_id, created_at),
                  INDEX ix_trades_taker (taker_order_id),
                  INDEX ix_trades_maker (maker_order_id)
                )
                """);
        addTradeColumnIfMissing("buyer_fee", "BIGINT NOT NULL DEFAULT 0");
        addTradeColumnIfMissing("seller_fee", "BIGINT NOT NULL DEFAULT 0");
        addTradeColumnIfMissing("fee", "BIGINT NOT NULL DEFAULT 0");
        for(String side:List.of("buyer","seller")) {
            String name="ix_trades_"+side+"_fees";
            if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='trades' AND index_name=?",Integer.class,name)==0)
                jdbc.execute("CREATE INDEX "+name+" ON trades ("+side+"_id,created_at,"+side+"_fee)");
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='trades' AND index_name='ix_trades_stock_sequence'",Integer.class)==0)
            jdbc.execute("CREATE INDEX ix_trades_stock_sequence ON trades (stock_id,id)");
    }

    private void addTradeColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'trades' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE trades ADD COLUMN " + name + " " + definition);
    }

    private void ensurePortfolioColumns() {
        boolean settledColumnAdded = addPortfolioColumnIfMissing("settled_quantity", "INT NOT NULL DEFAULT 0");
        addPortfolioColumnIfMissing("realized_profit_loss", "BIGINT NOT NULL DEFAULT 0");
        // Only rows from a pre-settlement schema are known to be settled. Do not
        // run this update on every restart; fresh rows are already settled.
        if (settledColumnAdded) jdbc.update("UPDATE portfolios SET settled_quantity = quantity WHERE quantity > 0");
    }

    private boolean addPortfolioColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'portfolios' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) {
            jdbc.execute("ALTER TABLE portfolios ADD COLUMN " + name + " " + definition);
            return true;
        }
        return false;
    }

    private void ensureSettlementTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS settlements (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  trade_id BIGINT NOT NULL UNIQUE,
                  buyer_id BIGINT NOT NULL,
                  seller_id BIGINT NOT NULL,
                  stock_id BIGINT NOT NULL,
                  quantity INT NOT NULL,
                  gross_amount BIGINT NOT NULL,
                  buyer_fee BIGINT NOT NULL DEFAULT 0,
                  seller_fee BIGINT NOT NULL DEFAULT 0,
                  buyer_quantity_before INT NULL,
                  buyer_settled_quantity_before INT NULL,
                  buyer_average_price_before BIGINT NULL,
                  buyer_realized_profit_loss_before BIGINT NULL,
                  seller_quantity_before INT NULL,
                  seller_settled_quantity_before INT NULL,
                  seller_average_price_before BIGINT NULL,
                  seller_realized_profit_loss_before BIGINT NULL,
                  settlement_at TIMESTAMP NOT NULL,
                  status ENUM('PENDING', 'SETTLED', 'CANCELLED') NOT NULL DEFAULT 'PENDING',
                  settled_at TIMESTAMP NULL,
                  cancelled_at TIMESTAMP NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT fk_settlements_trade FOREIGN KEY (trade_id) REFERENCES trades(id),
                  CONSTRAINT fk_settlements_buyer FOREIGN KEY (buyer_id) REFERENCES users(id),
                  CONSTRAINT fk_settlements_seller FOREIGN KEY (seller_id) REFERENCES users(id),
                  CONSTRAINT fk_settlements_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_settlements_pending (status, settlement_at),
                  INDEX ix_settlements_buyer (buyer_id, status),
                  INDEX ix_settlements_seller (seller_id, status)
                )
                """);
        addSettlementColumnIfMissing("buyer_quantity_before", "INT NULL");
        addSettlementColumnIfMissing("buyer_settled_quantity_before", "INT NULL");
        addSettlementColumnIfMissing("buyer_average_price_before", "BIGINT NULL");
        addSettlementColumnIfMissing("buyer_realized_profit_loss_before", "BIGINT NULL");
        addSettlementColumnIfMissing("seller_quantity_before", "INT NULL");
        addSettlementColumnIfMissing("seller_settled_quantity_before", "INT NULL");
        addSettlementColumnIfMissing("seller_average_price_before", "BIGINT NULL");
        addSettlementColumnIfMissing("seller_realized_profit_loss_before", "BIGINT NULL");
        addSettlementColumnIfMissing("cancelled_at", "TIMESTAMP NULL");
        jdbc.execute("ALTER TABLE settlements MODIFY COLUMN status ENUM('PENDING', 'SETTLED', 'CANCELLED') NOT NULL DEFAULT 'PENDING'");
    }

    private void addSettlementColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'settlements' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE settlements ADD COLUMN " + name + " " + definition);
    }

    private void ensureDailySummaryTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS daily_market_summaries (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  stock_id BIGINT NOT NULL,
                  trading_date DATE NOT NULL,
                  open_price BIGINT NOT NULL,
                  close_price BIGINT NOT NULL,
                  total_volume BIGINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uq_daily_summary_stock_date (stock_id, trading_date),
                  CONSTRAINT fk_daily_summary_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_daily_summary_date (trading_date)
                )
                """);
    }

    private void ensureSimulationState() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS simulation_state (
                  id TINYINT PRIMARY KEY,
                  tick BIGINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.update("INSERT IGNORE INTO simulation_state (id, tick) VALUES (1, 0)");
    }

    private void ensureNewsPriceState() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS news_price_state (
                  stock_id BIGINT PRIMARY KEY,
                  applied_bias DECIMAL(8,6) NOT NULL DEFAULT 0,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  CONSTRAINT fk_news_price_state_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
                )
                """);
    }

    private void ensureMarketLock() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS market_locks (
                  id TINYINT PRIMARY KEY
                )
                """);
        jdbc.update("INSERT IGNORE INTO market_locks (id) VALUES (1)");
    }
}
