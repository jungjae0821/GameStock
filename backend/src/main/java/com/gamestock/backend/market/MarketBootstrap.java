package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Startup data steps split out of {@link MarketService#initializeData()}: the schema
 * migration lock and the idempotent rows every start must find (demo user, renamed or
 * retired legacy listings, trader bot accounts, seed price history).
 * Not a Spring bean: MarketService constructs it and keeps the startup order, the
 * simulation fields and the steps that touch in-memory engine state. Market lock and
 * startup transactions are taken through MarketService so there is one way to get them.
 */
final class MarketBootstrap {
    private static final Logger log = LoggerFactory.getLogger(MarketBootstrap.class);
    private static final String SCHEMA_MIGRATION_LOCK = "gamestock:schema-migration";
    private static final long TRADER_BOT_STARTING_CASH = 1_000_000L;

    private final MarketService market;
    private final JdbcTemplate jdbc;

    MarketBootstrap(MarketService market, JdbcTemplate jdbc) {
        this.market = market;
        this.jdbc = jdbc;
    }

    /**
     * Keep schema changes exclusive across accidental overlapping starts. The
     * lock is held on a dedicated JDBC connection while the migration uses its
     * normal pool connections, and is released even when a DDL step fails.
     */
    void migrateSchemaWithLock(int lockTimeoutSeconds, UserFeatureService userFeatures,
                               TradingProtectionService protection, PriceMetricService priceMetrics,
                               TitleService titles) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            boolean locked = false;
            int timeout = Math.max(0, lockTimeoutSeconds);
            log.info("Waiting for database schema migration lock (timeout={}s)", timeout);
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                statement.setString(1, SCHEMA_MIGRATION_LOCK);
                statement.setInt(2, timeout);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new IllegalStateException("Could not acquire database schema migration lock within " + timeout + " seconds");
                    }
                }
            }
            locked = true;
            long started = System.nanoTime();
            try {
                new MarketSchema(jdbc).migrate(userFeatures, protection, priceMetrics, titles);
                log.info("Database schema migration completed in {} ms",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                return null;
            } finally {
                if (locked) {
                    try (var release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        release.setString(1, SCHEMA_MIGRATION_LOCK);
                        release.execute();
                    } catch (SQLException releaseError) {
                        log.warn("Could not release database schema migration lock", releaseError);
                    }
                }
            }
        });
    }

    void ensureDemoUser() {
        jdbc.update("""
                INSERT IGNORE INTO users (username, password_hash, nickname, cash)
                VALUES (?, ?, ?, ?)
                """, MarketService.DEMO_USERNAME, "demo", "Demo User", 1_000_000L);
    }

    /** Earlier listings renamed in place, and one retired while its history is kept. */
    void migrateLegacyListings() {
        renameExistingStock("NEXA", "UMA", "네사: 크로니클", "우마무스메 프리티더비");
        renameExistingStock("STAR", "BA", "스타라이트 아레나", "블루 아카이브");
        renameExistingStock("MOMO", "GOV", "모모 팜", "승리의 여신: 니케");
        retireLegacyStock("VOID");
    }

    void ensureTraderBots(List<BotProfile> traderProfiles) {
        for(int start=0;start<traderProfiles.size();start+=200) {
            var group=traderProfiles.subList(start,Math.min(start+200,traderProfiles.size()));
            List<Object> args=new ArrayList<>();
            for(BotProfile profile:group) {
                args.add(profile.username());args.add(MarketService.TRADER_BOT_PASSWORD);args.add(profile.nickname());args.add(TRADER_BOT_STARTING_CASH);
            }
            // Existing funds are never reset. New participants receive finite initial capital once.
            jdbc.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES "
                    +String.join(",",Collections.nCopies(group.size(),"(?,?,?,?)"))
                    +" ON DUPLICATE KEY UPDATE nickname=IF(password_hash='TRADER',VALUES(nickname),nickname)",args.toArray());
        }
    }

    void removeDefaultEvents() {
        jdbc.update("""
                DELETE FROM market_events
                WHERE title IN (?, ?, ?)
                """, "대규모 시즌 업데이트 적용", "경쟁작 출시 예고",
                "글로벌 누적 이용자 1,000만 달성");
    }

    void seedPriceHistory() {
        jdbc.update("""
                INSERT INTO stock_price_history (stock_id, price)
                SELECT s.id, s.current_price FROM stocks s
                WHERE NOT EXISTS (
                    SELECT 1 FROM stock_price_history h WHERE h.stock_id = s.id
                )
                """);
    }

    private void renameExistingStock(String oldCode, String newCode, String oldName, String newName) {
        jdbc.update("UPDATE games SET name = ? WHERE name = ?", newName, oldName);
        jdbc.update("UPDATE stocks SET stock_code = ? WHERE stock_code = ?", newCode, oldCode);
    }

    private void retireLegacyStock(String code) {
        market.startupTransaction().executeWithoutResult(status -> {
            market.acquireMarketLock();
            var baskets = new DerivativeBaskets(jdbc);
            baskets.lock();
            // Keep holdings, news and financial history, including all foreign-key targets.
            // An explicit later admin decision must survive subsequent restarts.
            jdbc.update("""
                    INSERT IGNORE INTO admin_stock_state(stock_id,state,reason)
                    SELECT id,'DELISTED','구형 종목 운영 종료' FROM stocks WHERE stock_code=?
                    """, code);
            var orders = jdbc.queryForList("""
                    SELECT o.id,o.user_id,o.reserved_cash FROM orders o JOIN stocks s ON s.id=o.stock_id
                    JOIN admin_stock_state a ON a.stock_id=s.id
                    WHERE s.stock_code=? AND a.state<>'ACTIVE' AND o.status='OPEN'
                    ORDER BY o.user_id,o.id FOR UPDATE
                    """, code);
            for (var order : orders) {
                long refund = ((Number) order.get("reserved_cash")).longValue();
                if (refund > 0) jdbc.update("UPDATE users SET cash=cash+? WHERE id=?", refund, order.get("user_id"));
                jdbc.update("UPDATE orders SET status='CANCELLED',remaining_quantity=0,reserved_cash=0,reserved_quantity=0 WHERE id=?", order.get("id"));
            }
            // Cancellation/refunds, retirement and value-preserving reconstitution commit together.
            baskets.rebalance();
        });
    }
}
