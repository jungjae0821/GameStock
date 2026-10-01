package com.gamestock.backend.market;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Random;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import static com.gamestock.backend.market.MarketModels.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

@Service
public class MarketService {
    private static final Logger log = LoggerFactory.getLogger(MarketService.class);
    private static final long STARTING_CASH = 1_000_000L;
    private static final ZoneId MISSION_ZONE = ZoneId.of("Asia/Seoul");
    private static final Map<String, Long> MISSION_REWARDS = Map.of(
            "market", 10_000L,
            "news", 10_000L,
            "watch", 10_000L);
    /** GameStock charges a small, transparent 0.10% commission per side. */
    private static final double TRADING_FEE_RATE = 0.001;
    /** A normal bot matching cycle may move the current price by at most +/-0.5%. */
    private static final double BOT_PRICE_STEP_RATE = 0.005;
    /** A bot may react up to +/-1% during a special-news matching cycle. */
    private static final double BOT_SPECIAL_PRICE_STEP_RATE = 0.01;
    /** Per-headline news influence limits. */
    private static final double NEWS_SINGLE_BASE_RATE = 0.02;
    private static final double NEWS_SINGLE_SPECIAL_RATE = 0.05;
    private static final double NEWS_MAJOR_INCIDENT_RATE = 0.10;
    /** Portion of the aggregate news move applied directly to the reference price. */
    private static final double NEWS_DIRECT_RATE_SHARE = 0.40;
    /** News tilts bot order sizes while preserving both buy and sell liquidity. */
    private static final double BOT_NEWS_SIZE_SENSITIVITY = 3.0;
    /** Bot executions move the quote only partially; the exact share varies per tick. */
    private static final double BOT_TRADE_IMPACT_MIN = 0.12;
    private static final double BOT_TRADE_IMPACT_MAX = 0.42;
    /** Keep an internal cushion below the bot band so automated flow cannot pin a limit. */
    private static final double BOT_LIMIT_HEADROOM_RATE = 0.01;
    private static final double BOT_NEAR_LIMIT_POSITION = 0.75;
    private static final double BOT_LIMIT_REVERSAL_PROBABILITY = 0.62;
    /** Twenty-four-hour aggregate news influence limits. */
    private static final double NEWS_24H_BASE_RATE = 0.05;
    private static final double NEWS_24H_SPECIAL_RATE = 0.10;
    /** Occasionally let one bot submit a larger liquidity-sized order so the
     * tape has natural bursts without allowing an unbounded quantity. */
    private static final double BOT_BURST_PROBABILITY = 0.04;
    /** Keep bot quotes visible without letting bot volume feed back into huge orders. */
    private static final double BOT_NORMAL_VOLUME_PARTICIPATION = 0.02;
    private static final double BOT_BURST_VOLUME_PARTICIPATION = 0.06;
    private static final int BOT_NORMAL_ORDER_MAX = 900;
    private static final int BOT_BURST_ORDER_MAX = 1_800;
    private static final int BOT_INITIAL_INVENTORY = 400;
    /** A bot can choose to wait instead of submitting an order at every decision time. */
    private static final double BOT_ACTIVE_ACTION_PROBABILITY = 0.78;
    private static final double BOT_LIMIT_ORDER_PROBABILITY = 0.82;
    /** Scores are produced by NewsFeedService's -10..+10 classifier. */
    private static final double NEWS_SPECIAL_IMPACT_THRESHOLD = 6.0;
    private static final double NEWS_MAJOR_INCIDENT_IMPACT_THRESHOLD = -8.0;
    private static final int MAX_BOT_OPEN_ORDERS_PER_SIDE = 14;
    /** LP keeps both sides visible even when one independent side schedule is consumed first. */
    private static final int LP_MIN_OPEN_ORDERS_PER_SIDE = 2;
    private static final long LP_MIN_OPEN_QUANTITY_PER_SIDE = 20L;
    private static final double LP_MAX_SIDE_IMBALANCE = 4.0;
    private static final int LP_REBALANCE_ATTEMPTS = 3;
    /** Keep the first few legal levels visible around the last traded price. */
    private static final int LP_NEAR_QUOTE_LEVELS = 3;
    private static final int LP_MAX_NEAR_QUOTE_DISTANCE_TICKS = 4;
    private static final int BOT_ORDER_LIFETIME_MINUTES = 20;
    /** Human-like participant bots use the normal user order path and ranking. */
    private static final String TRADER_BOT_PASSWORD = "TRADER";
    private static final long TRADER_BOT_STARTING_CASH = 1_000_000L;
    private static final int TRADER_BOT_MAX_OPEN_ORDERS = 3;
    private static final int TRADER_BOT_MAX_ORDER_QUANTITY = 50;
    private static final List<TraderBotProfile> TRADER_BOT_PROFILES = List.of(
            new TraderBotProfile("trader_bot_01", "주식하는 슈엔", TraderStyle.MOMENTUM),
            new TraderBotProfile("trader_bot_02", "고점에 물린 드레이크", TraderStyle.CONTRARIAN),
            new TraderBotProfile("trader_bot_03", "물타기 실패한 라플라스", TraderStyle.VALUE),
            new TraderBotProfile("trader_bot_04", "빚투하는 맥스웰", TraderStyle.INTRADAY),
            new TraderBotProfile("trader_bot_05", "추세 타는 아리스", TraderStyle.MOMENTUM),
            new TraderBotProfile("trader_bot_06", "하락장 줍는 시로코", TraderStyle.CONTRARIAN),
            new TraderBotProfile("trader_bot_07", "저평가만 보는 호시노", TraderStyle.VALUE),
            new TraderBotProfile("trader_bot_08", "장중 매매 카즈사", TraderStyle.INTRADAY),
            new TraderBotProfile("trader_bot_09", "뉴스 따라가는 유즈", TraderStyle.MOMENTUM),
            new TraderBotProfile("trader_bot_10", "반등 기다리는 라피", TraderStyle.CONTRARIAN),
            new TraderBotProfile("trader_bot_11", "분할매수 미야코", TraderStyle.VALUE),
            new TraderBotProfile("trader_bot_12", "마감 전 매매 벨", TraderStyle.INTRADAY),
            new TraderBotProfile("trader_bot_13", "돌파 매수 조던", TraderStyle.MOMENTUM),
            new TraderBotProfile("trader_bot_14", "고점 탈출 파머", TraderStyle.CONTRARIAN),
            new TraderBotProfile("trader_bot_15", "현금 지키는 히카리", TraderStyle.VALUE),
            new TraderBotProfile("trader_bot_16", "초단타 아니스", TraderStyle.INTRADAY),
            new TraderBotProfile("trader_bot_17", "차트 믿는 미야비", TraderStyle.MOMENTUM),
            new TraderBotProfile("trader_bot_18", "역추세 타는 엘렌", TraderStyle.CONTRARIAN),
            new TraderBotProfile("trader_bot_19", "조용히 모으는 엔비", TraderStyle.VALUE),
            new TraderBotProfile("trader_bot_20", "오전만 거래하는 시시아", TraderStyle.INTRADAY));
    private static final int USER_ORDER_WINDOW_SECONDS = 10;
    private static final int USER_ORDER_LIMIT = 20;
    private static final int DUPLICATE_ORDER_WINDOW_SECONDS = 2;
    private static final String DEMO_USERNAME = "demo";
    private static final Pattern NEWS_SOURCE_PATTERN = Pattern.compile("출처:\\s*(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final UserFeatureService userFeatures;
    private final TradingProtectionService protection;
    @Value("${gamestock.simulation.seed:20260910}")
    private long simulationSeed;
    private Random tickRandom = new Random(20260910L);
    private final long runtimeNoise = System.nanoTime();
    private long demoUserId;

    public MarketService(ApplicationEventPublisher events, JdbcTemplate jdbc, UserFeatureService userFeatures,
                         TradingProtectionService protection) {
        this.events = events;
        this.jdbc = jdbc;
        this.userFeatures = userFeatures;
        this.protection = protection;
    }

    @PostConstruct
    @Transactional
    public void initializeData() {
        ensurePriceHistoryTable();
        ensureAuthenticationTables();
        ensureMarketEventColumns();
        ensureTradeTable();
        ensurePortfolioColumns();
        ensureSettlementTable();
        ensureDailySummaryTable();
        ensureSimulationState();
        ensureMarketLock();
        jdbc.update("""
                INSERT IGNORE INTO users (username, password_hash, nickname, cash)
                VALUES (?, ?, ?, ?)
                """, DEMO_USERNAME, "demo", "Demo User", 1_000_000L);

        insertGameIfMissing("우마무스메 프리티더비", "Cygames", "RPG");
        insertGameIfMissing("블루 아카이브", "Nexon", "RPG");
        insertGameIfMissing("승리의 여신: 니케", "ShiftUp", "RPG");
        insertGameIfMissing("젠레스 존 제로", "HoYoverse", "액션");

        renameExistingStock("NEXA", "UMA", "네사: 크로니클", "우마무스메 프리티더비");
        renameExistingStock("STAR", "BA", "스타라이트 아레나", "블루 아카이브");
        renameExistingStock("MOMO", "GOV", "모모 팜", "승리의 여신: 니케");
        removeExistingStock("VOID", "보이드 러너");

        insertStock("UMA", "우마무스메 프리티더비", 10_000L);
        insertStock("BA", "블루 아카이브", 10_000L);
        insertStock("GOV", "승리의 여신: 니케", 10_000L);
        insertStock("ZZZ", "젠레스 존 제로", 10_000L);
        ensureTraderBots();
        userFeatures.ensureTables();
        userFeatures.ensureDefaultTags();
        seedPriceHistory();
        seedDailySummaries();
        protection.ensureTables();
        normalizeOpenOrderPrices();

        removeDefaultEvents();
        initializeNewsPriceState();

        demoUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, DEMO_USERNAME);
        // Convert any rows created by the former T+1 implementation to the
        // new immediate-settlement state during startup.
        settleDuePayments();
    }

    private void ensureTraderBots() {
        for (TraderBotProfile profile : TRADER_BOT_PROFILES) {
            jdbc.update("""
                    INSERT IGNORE INTO users (username, password_hash, nickname, cash)
                    VALUES (?, ?, ?, ?)
                    """, profile.username(), TRADER_BOT_PASSWORD, profile.nickname(), TRADER_BOT_STARTING_CASH);
            jdbc.update("UPDATE users SET nickname = ? WHERE username = ? AND password_hash = ?",
                    profile.nickname(), profile.username(), TRADER_BOT_PASSWORD);
        }
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
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'remaining_quantity'", Integer.class);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE orders ADD COLUMN remaining_quantity INT NOT NULL DEFAULT 0");
        addOrderColumnIfMissing("reserved_cash", "BIGINT NOT NULL DEFAULT 0");
        addOrderColumnIfMissing("reserved_quantity", "INT NOT NULL DEFAULT 0");
        addOrderColumnIfMissing("expires_at", "TIMESTAMP NULL");
        jdbc.update("UPDATE orders SET remaining_quantity = quantity WHERE remaining_quantity = 0 AND status = 'OPEN'");
        jdbc.execute("ALTER TABLE orders MODIFY COLUMN status ENUM('OPEN', 'FILLED', 'PARTIAL', 'CANCELLED') NOT NULL DEFAULT 'OPEN'");
    }

    private void addOrderColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE orders ADD COLUMN " + name + " " + definition);
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
    }

    private void insertStock(String code, String name, long price) {
        jdbc.update("""
                INSERT IGNORE INTO stocks (game_id, stock_code, current_price, previous_price, total_volume)
                SELECT id, ?, ?, ?, ? FROM games WHERE name = ?
                """, code, price, price, 0L, name);
    }

    private void insertGameIfMissing(String name, String developer, String genre) {
        jdbc.update("""
                INSERT INTO games (name, developer, genre)
                SELECT ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM games WHERE name = ?)
                """, name, developer, genre, name);
    }

    private void renameExistingStock(String oldCode, String newCode, String oldName, String newName) {
        jdbc.update("UPDATE games SET name = ? WHERE name = ?", newName, oldName);
        jdbc.update("UPDATE stocks SET stock_code = ? WHERE stock_code = ?", newCode, oldCode);
    }

    private void removeExistingStock(String code, String gameName) {
        jdbc.update("DELETE FROM market_events WHERE stock_id IN (SELECT id FROM stocks WHERE stock_code = ?)", code);
        // 체결 이력이 주문/종목을 외래 키로 참조하므로 주문보다 먼저 정리한다.
        jdbc.update("DELETE FROM trades WHERE stock_id IN (SELECT id FROM stocks WHERE stock_code = ?)", code);
        jdbc.update("DELETE FROM orders WHERE stock_id IN (SELECT id FROM stocks WHERE stock_code = ?)", code);
        jdbc.update("DELETE FROM portfolios WHERE stock_id IN (SELECT id FROM stocks WHERE stock_code = ?)", code);
        jdbc.update("DELETE FROM stocks WHERE stock_code = ?", code);
        jdbc.update("DELETE FROM games WHERE name = ? AND NOT EXISTS (SELECT 1 FROM stocks WHERE game_id = games.id)", gameName);
    }

    private void removeDefaultEvents() {
        jdbc.update("""
                DELETE FROM market_events
                WHERE title IN (?, ?, ?)
                """, "대규모 시즌 업데이트 적용", "경쟁작 출시 예고",
                "글로벌 누적 이용자 1,000만 달성");
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

    private void seedPriceHistory() {
        jdbc.update("""
                INSERT INTO stock_price_history (stock_id, price)
                SELECT s.id, s.current_price FROM stocks s
                WHERE NOT EXISTS (
                    SELECT 1 FROM stock_price_history h WHERE h.stock_id = s.id
                )
                """);
    }

    public synchronized List<Stock> stocks() {
        return jdbc.query("""
                SELECT stock_code, g.name, g.genre, current_price, previous_price, total_volume
                FROM stocks s JOIN games g ON g.id = s.game_id
                ORDER BY s.id
                """, (rs, row) -> new Stock(
                rs.getString("stock_code"),
                rs.getString("name"),
                rs.getString("genre"),
                rs.getLong("current_price"),
                changePercent(rs.getLong("current_price"), rs.getLong("previous_price")),
                rs.getLong("total_volume"), protection.restriction(rs.getString("stock_code"))));
    }

    public synchronized List<MarketEvent> marketEvents() {
        List<MarketEvent> relevant = jdbc.query("""
                SELECT s.stock_code, e.title, e.impact, e.source, e.description, e.published_at,
                       s.current_price, s.previous_price,
                       COALESCE(e.price_at_publish, (
                           SELECT h.price FROM stock_price_history h
                           WHERE h.stock_id = e.stock_id
                             AND h.recorded_at <= COALESCE(e.published_at, e.created_at)
                           ORDER BY h.recorded_at DESC, h.id DESC LIMIT 1
                       ), s.current_price) AS price_at_publish
                FROM market_events e LEFT JOIN stocks s ON s.id = e.stock_id
                WHERE e.event_type = 'NEWS'
            ORDER BY COALESCE(e.published_at, e.created_at) DESC, e.id DESC
            LIMIT 200
                """, this::toMarketEvent).stream()
                .filter(this::isRelevantNews)
                .toList();
        List<MarketEvent> updates = relevant.stream()
                .filter(event -> "업데이트 노트".equals(event.source()))
                .toList();
        List<MarketEvent> media = relevant.stream()
                .filter(event -> !"업데이트 노트".equals(event.source()))
                .toList();
        // 한 게임의 글이 최신이라는 이유로 다른 게임의 공식 글을
        // 전부 밀어내지 않도록, 종류별로 게임마다 최신 2개씩 확보한다.
        updates = takeLatestPerStock(updates, 2);
        media = takeLatestPerStock(media, 2);
        // 두 종류를 모두 확보하되, 화면에서는 게시 시각의 전체 흐름으로
        // 섞어서 보여준다. 업데이트 노트를 억지로 상단에 고정하지 않는다.
        return java.util.stream.Stream.concat(updates.stream(), media.stream())
                .sorted(java.util.Comparator.comparing(this::publishedInstant,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    private List<MarketEvent> takeLatestPerStock(List<MarketEvent> events, int perStock) {
        Map<String, Integer> counts = new HashMap<>();
        return events.stream()
                .filter(event -> {
                    String stockCode = event.stockCode();
                    int count = counts.getOrDefault(stockCode, 0);
                    if (count >= perStock) return false;
                    counts.put(stockCode, count + 1);
                    return true;
                })
                .toList();
    }

    private Instant publishedInstant(MarketEvent event) {
        if (event.publishedAt() == null || event.publishedAt().isBlank()) return null;
        try {
            return Instant.parse(event.publishedAt());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** GameStock은 장 마감 없이 24시간 주문을 접수하는 게임형 시장이다. */
    public synchronized MarketStatus marketStatus() {
        return protection.marketStatus();
    }

    public synchronized List<MarketEvent> stockNews(String code) {
        List<MarketEvent> candidates = jdbc.query("""
                SELECT s.stock_code, e.title, e.impact, e.source, e.description, e.published_at,
                       s.current_price, s.previous_price,
                       COALESCE(e.price_at_publish, (
                           SELECT h.price FROM stock_price_history h
                           WHERE h.stock_id = e.stock_id
                             AND h.recorded_at <= COALESCE(e.published_at, e.created_at)
                           ORDER BY h.recorded_at DESC, h.id DESC LIMIT 1
                       ), s.current_price) AS price_at_publish
                FROM market_events e JOIN stocks s ON s.id = e.stock_id
                WHERE e.event_type = 'NEWS' AND s.stock_code = ?
                ORDER BY COALESCE(e.published_at, e.created_at) DESC, e.id DESC
                LIMIT 100
                """, this::toMarketEvent, code.toUpperCase(Locale.ROOT));
        Set<String> seen = new HashSet<>();
        return candidates.stream()
                .filter(this::isRelevantNews)
                // Google News can publish the same article with minor source/title
                // variants. Count one canonical source only once toward the five.
                .filter(event -> seen.add(newsIdentity(event)))
                .limit(5)
                .toList();
    }

    private String newsIdentity(MarketEvent event) {
        Matcher matcher = NEWS_SOURCE_PATTERN.matcher(event.description() == null ? "" : event.description());
        return matcher.find() ? matcher.group(1) : event.title();
    }

    private boolean isRelevantNews(MarketEvent event) {
        return "업데이트 노트".equals(event.source())
                || event.stockCode() == null
                || NewsRelevance.isRelevant(event.stockCode(), event.title(), event.description());
    }

    private MarketEvent toMarketEvent(ResultSet rs, int rowNumber) throws SQLException {
        double rawImpact = rs.getDouble("impact");
        int impact = (int) Math.round(rawImpact);
        String sentiment = impact > 0 ? "positive" : impact < 0 ? "negative" : "neutral";
        long currentPrice = rs.getLong("current_price");
        long priceAtPublish = rs.getLong("price_at_publish");
        double priceChangePercent = changePercent(currentPrice, priceAtPublish);
        String priceDirection = priceChangePercent > 0 ? "up" : priceChangePercent < 0 ? "down" : "flat";
        String reason = priceReason(priceChangePercent, impact);
        var publishedAt = rs.getTimestamp("published_at");
        String published = publishedAt == null ? null : databaseInstant(publishedAt).toString();
        return new MarketEvent(rs.getString("stock_code"), rs.getString("title"), impact,
                sentiment, rs.getString("source"), rs.getString("description"), published, priceAtPublish, priceChangePercent,
                priceDirection, reason);
    }

    private String priceReason(double priceChangePercent, int impact) {
        if (priceChangePercent > 0 && impact > 0)
            return "뉴스 영향과 최근 상승 흐름이 함께 나타나 가격이 오르고 있습니다.";
        if (priceChangePercent < 0 && impact < 0)
            return "뉴스 영향과 최근 하락 흐름이 함께 나타나 가격이 내리고 있습니다.";
        if (priceChangePercent > 0 && impact < 0)
            return "하락 요인으로 해석되는 뉴스가 있지만 최근 거래 흐름은 상승세입니다.";
        if (priceChangePercent < 0 && impact > 0)
            return "상승 요인으로 해석되는 뉴스가 있지만 최근 거래 흐름은 하락세입니다.";
        if (priceChangePercent > 0)
            return "최근 거래 흐름을 따라 가격이 상승하고 있습니다.";
        if (priceChangePercent < 0)
            return "최근 거래 흐름을 따라 가격이 하락하고 있습니다.";
        if (impact > 0) return "상승 요인으로 해석되는 뉴스가 등록됐지만 가격은 보합입니다.";
        if (impact < 0) return "하락 요인으로 해석되는 뉴스가 등록됐지만 가격은 보합입니다.";
        return "최근 가격과 뉴스 영향도가 보합 상태입니다.";
    }

    public synchronized List<RankingEntry> ranking() {
        settleDuePayments();
        List<RankingEntry> entries = jdbc.query("""
                SELECT u.nickname, NULL AS profile_image_url, u.cash,
                       COALESCE((SELECT SUM(o.reserved_cash) FROM orders o WHERE o.user_id = u.id AND o.status = 'OPEN'), 0) AS reserved_cash,
                       COALESCE((SELECT SUM(st.gross_amount - st.seller_fee) FROM settlements st WHERE st.seller_id = u.id AND st.status = 'PENDING'), 0) AS unsettled_cash,
                       COALESCE((SELECT SUM(ar.reward_cash) FROM attendance_rewards ar WHERE ar.user_id = u.id), 0) AS attendance_reward_cash,
                       COALESCE(SUM(CASE WHEN p.quantity > 0 THEN p.quantity * s.current_price ELSE 0 END), 0) AS asset_value
                FROM users u
                LEFT JOIN portfolios p ON p.user_id = u.id
                LEFT JOIN stocks s ON s.id = p.stock_id
                WHERE u.password_hash <> 'BOT' AND u.username <> 'demo'
                  AND COALESCE(u.role, 'USER') <> 'ADMIN'
                GROUP BY u.id, u.nickname, u.cash
                ORDER BY (u.cash + reserved_cash + asset_value) DESC, u.id ASC
                LIMIT 100
                """, (rs, row) -> {
            long assetValue = rs.getLong("asset_value");
            long cash = rs.getLong("cash");
            long reservedCash = rs.getLong("reserved_cash");
            long unsettledCash = rs.getLong("unsettled_cash");
            long attendanceRewardCash = rs.getLong("attendance_reward_cash");
            long totalAsset = cash + reservedCash + unsettledCash + assetValue;
            double changePercent = (totalAsset - STARTING_CASH - attendanceRewardCash) * 100.0 / STARTING_CASH;
            return new RankingEntry(0, rs.getString("nickname"), rs.getString("profile_image_url"), totalAsset, assetValue, cash, changePercent);
        });
        List<RankingEntry> ranked = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            RankingEntry entry = entries.get(index);
            ranked.add(new RankingEntry(index + 1, entry.nickname(), entry.profileImageUrl(), entry.totalAsset(), entry.assetValue(), entry.cash(), entry.changePercent()));
        }
        return ranked;
    }

    public synchronized Portfolio portfolio(long userId) {
        settleDuePayments();
        expireOrders();
        return portfolioUnsafe(userId);
    }

    @Transactional(readOnly = true)
    public synchronized DailyMissionStatus dailyMissions(long userId) {
        return dailyMissionsAt(userId, Instant.now());
    }

    private DailyMissionStatus dailyMissionsAt(long userId, Instant now) {
        LocalDate day = now.atZone(MISSION_ZONE).toLocalDate();
        List<String> completed = jdbc.queryForList("SELECT mission_id FROM mission_rewards WHERE user_id = ? AND rewarded_on = ? ORDER BY mission_id",
                String.class, userId, java.sql.Date.valueOf(day));
        return new DailyMissionStatus(day.toString(), day.plusDays(1).atStartOfDay(MISSION_ZONE).toInstant().toString(),
                now.toString(), completed.stream().filter(MISSION_REWARDS::containsKey).toList());
    }

    @Transactional
    public synchronized MissionRewardResult rewardMission(String missionId, long userId) {
        Long reward = MISSION_REWARDS.get(missionId);
        if (reward == null) throw new IllegalArgumentException("존재하지 않는 미션입니다.");
        LocalDate day = LocalDate.now(MISSION_ZONE);
        int inserted = jdbc.update("INSERT IGNORE INTO mission_rewards (user_id, mission_id, rewarded_on, reward_cash) VALUES (?, ?, ?, ?)",
                userId, missionId, java.sql.Date.valueOf(day), reward);
        if (inserted > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reward, userId);
        Portfolio portfolio = portfolioUnsafe(userId);
        return new MissionRewardResult(inserted > 0 ? reward : 0, inserted > 0, portfolio,
                dailyMissionsAt(userId, Instant.now()));
    }

    public synchronized List<ActiveOrder> activeOrders(long userId) {
        settleDuePayments();
        expireOrders();
        return jdbc.query("""
                SELECT o.id, s.stock_code, o.side, o.quantity, o.remaining_quantity, COALESCE(o.price, 0) AS price,
                       o.status, o.order_type, COALESCE(o.reserved_cash, 0) AS reserved_cash,
                       COALESCE(o.reserved_quantity, 0) AS reserved_quantity, o.created_at, o.expires_at
                FROM orders o JOIN stocks s ON s.id = o.stock_id
                WHERE o.user_id = ? AND o.status = 'OPEN'
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 5
                """, (rs, row) -> new ActiveOrder(
                rs.getLong("id"), rs.getString("stock_code"), rs.getString("side"),
                rs.getInt("quantity"), rs.getInt("remaining_quantity"), rs.getLong("price"),
                rs.getString("status"), rs.getString("order_type"), rs.getLong("reserved_cash"),
                rs.getInt("reserved_quantity"), databaseInstant(rs.getTimestamp("created_at")).toString(),
                rs.getTimestamp("expires_at") == null ? null : databaseInstant(rs.getTimestamp("expires_at")).toString()), userId);
    }

    /** The user's recently completed fills. Cash and shares settle immediately. */
    public synchronized List<SettlementEntry> settlements(long userId) {
        settleDuePayments();
        return jdbc.query("""
                SELECT x.id, x.side, s.stock_code, x.quantity, x.gross_amount, x.fee,
                       x.net_amount, x.status, x.settlement_at, x.created_at, x.cancellable
                FROM (
                  SELECT st.id, st.stock_id, st.quantity, st.gross_amount,
                         CASE WHEN st.buyer_id = ? THEN 'BUY' ELSE 'SELL' END AS side,
                         CASE WHEN st.buyer_id = ? THEN st.buyer_fee ELSE st.seller_fee END AS fee,
                         CASE WHEN st.buyer_id = ? THEN st.gross_amount + st.buyer_fee
                              ELSE st.gross_amount - st.seller_fee END AS net_amount,
                         st.status, st.settlement_at, st.created_at,
                         CASE WHEN st.status = 'PENDING'
                                    AND st.buyer_quantity_before IS NOT NULL
                                    AND st.buyer_settled_quantity_before IS NOT NULL
                                    AND st.buyer_average_price_before IS NOT NULL
                                    AND st.buyer_realized_profit_loss_before IS NOT NULL
                                    AND st.seller_quantity_before IS NOT NULL
                                    AND st.seller_settled_quantity_before IS NOT NULL
                                    AND st.seller_average_price_before IS NOT NULL
                                    AND st.seller_realized_profit_loss_before IS NOT NULL
                              THEN TRUE ELSE FALSE END AS cancellable
                  FROM settlements st
                  WHERE (st.buyer_id = ? OR st.seller_id = ?) AND st.status = 'SETTLED'
                ) x JOIN stocks s ON s.id = x.stock_id
                ORDER BY x.created_at DESC, x.id DESC
                LIMIT 5
                """, (rs, row) -> new SettlementEntry(
                rs.getLong("id"), rs.getString("side"), rs.getString("stock_code"),
                rs.getInt("quantity"), rs.getLong("gross_amount"), rs.getLong("fee"),
                rs.getLong("net_amount"), rs.getString("status"),
                databaseInstant(rs.getTimestamp("settlement_at")).toString(),
                databaseInstant(rs.getTimestamp("created_at")).toString(),
                rs.getBoolean("cancellable")),
                userId, userId, userId, userId, userId);
    }

    /**
     * Compatibility rollback for legacy pending fills. New fills are settled
     * immediately and therefore cannot enter this path. If an older pending
     * row is cancelled, both participants' holdings are rebuilt from their
     * earliest saved snapshot and the remaining trade ledger.
     */
    @Transactional
    public synchronized void cancelSettlement(long settlementId, long userId) {
        acquireMarketLock();
        settleDuePayments();
        List<CancelableSettlement> rows = jdbc.query("""
                SELECT id, trade_id, buyer_id, seller_id, stock_id, quantity, gross_amount,
                       buyer_fee, seller_fee, created_at,
                       buyer_quantity_before, buyer_settled_quantity_before,
                       buyer_average_price_before, buyer_realized_profit_loss_before,
                       seller_quantity_before, seller_settled_quantity_before,
                       seller_average_price_before, seller_realized_profit_loss_before
                FROM settlements
                WHERE id = ? AND status = 'PENDING'
                FOR UPDATE
                """, (rs, row) -> new CancelableSettlement(
                rs.getLong("id"), rs.getLong("trade_id"), rs.getLong("buyer_id"),
                rs.getLong("seller_id"), rs.getLong("stock_id"), rs.getInt("quantity"),
                rs.getLong("gross_amount"), rs.getLong("buyer_fee"), rs.getLong("seller_fee"),
                databaseInstant(rs.getTimestamp("created_at")),
                (Integer) rs.getObject("buyer_quantity_before"),
                (Integer) rs.getObject("buyer_settled_quantity_before"),
                (Long) rs.getObject("buyer_average_price_before"),
                (Long) rs.getObject("buyer_realized_profit_loss_before"),
                (Integer) rs.getObject("seller_quantity_before"),
                (Integer) rs.getObject("seller_settled_quantity_before"),
                (Long) rs.getObject("seller_average_price_before"),
                (Long) rs.getObject("seller_realized_profit_loss_before")), settlementId);
        if (rows.isEmpty()) throw new IllegalArgumentException("취소할 수 있는 미결제 거래가 없습니다.");
        CancelableSettlement settlement = rows.get(0);
        if (settlement.buyerId() != userId && settlement.sellerId() != userId)
            throw new IllegalArgumentException("본인의 미결제 거래만 취소할 수 있습니다.");
        if (!settlement.hasSnapshot())
            throw new IllegalArgumentException("이 거래는 이전 버전에서 체결되어 취소할 수 없습니다.");
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?",
                settlement.grossAmount() + settlement.buyerFee(), settlement.buyerId());
        jdbc.update("UPDATE settlements SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP WHERE id = ?",
                settlement.id());
        rebuildHoldingFromLedger(settlement.stockId(), settlement.buyerId());
        if (settlement.sellerId() != settlement.buyerId())
            rebuildHoldingFromLedger(settlement.stockId(), settlement.sellerId());
        events.publishEvent(new MarketChangedEvent(snapshot()));
    }

    @Transactional
    public synchronized void cancelOrder(long orderId, long userId) {
        acquireMarketLock();
        settleDuePayments();
        expireOrders();
        List<OrderReservation> matches = jdbc.query("""
                SELECT id, user_id, reserved_cash
                FROM orders
                WHERE id = ? AND user_id = ? AND status = 'OPEN'
                FOR UPDATE
                """, (rs, row) -> new OrderReservation(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("reserved_cash")), orderId, userId);
        if (matches.isEmpty()) throw new IllegalArgumentException("취소할 수 있는 미체결 주문이 없습니다.");
        OrderReservation reservation = matches.get(0);
        if (reservation.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reservation.reservedCash(), userId);
        jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", orderId);
        events.publishEvent(new MarketChangedEvent(snapshot()));
    }

    public synchronized List<OrderHistory> orderHistory(String code, long userId) {
        settleDuePayments();
        expireOrders();
        return jdbc.query("""
                SELECT o.side, o.quantity, o.price, o.status, o.order_type, o.remaining_quantity, o.created_at,
                       COALESCE(SUM(CASE WHEN o.side = 'BUY' THEN t.buyer_fee ELSE t.seller_fee END), 0) AS fee,
                       CASE WHEN COUNT(t.id) = 0 THEN 'NONE'
                            WHEN SUM(CASE WHEN st.status = 'CANCELLED' THEN 1 ELSE 0 END) > 0 THEN 'CANCELLED'
                            WHEN SUM(CASE WHEN st.status = 'PENDING' THEN 1 ELSE 0 END) > 0 THEN 'PENDING'
                            ELSE 'SETTLED' END AS settlement_status,
                       MIN(CASE WHEN st.status = 'PENDING' THEN st.settlement_at ELSE NULL END) AS settlement_at
                FROM orders o JOIN stocks s ON s.id = o.stock_id
                LEFT JOIN trades t ON t.buy_order_id = o.id OR t.sell_order_id = o.id
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE o.user_id = ? AND s.stock_code = ?
                  AND o.created_at > COALESCE((SELECT account_reset_at FROM users WHERE id = ?), '1970-01-01')
                GROUP BY o.id, o.side, o.quantity, o.price, o.status, o.order_type, o.remaining_quantity, o.created_at
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 20
                """, (rs, row) -> new OrderHistory(
                rs.getString("side"),
                rs.getInt("quantity"),
                rs.getLong("price"),
                rs.getString("status"),
                rs.getString("order_type"),
                rs.getInt("remaining_quantity"),
                databaseInstant(rs.getTimestamp("created_at")).toString(),
                rs.getLong("fee"), rs.getString("settlement_status"),
                rs.getTimestamp("settlement_at") == null ? null : databaseInstant(rs.getTimestamp("settlement_at")).toString()),
                userId, code.toUpperCase(Locale.ROOT), userId);
    }

    /** 모든 사용자의 익명 체결 내역. 개인별 주문 API와 분리해 공개한다. */
    public synchronized List<PublicTrade> publicTrades(String code) {
        settleDuePayments();
        return jdbc.query("""
                SELECT t.aggressor_side AS side, t.quantity, t.price, taker.order_type, t.created_at
                FROM trades t
                JOIN stocks s ON s.id = t.stock_id
                JOIN orders taker ON taker.id = t.taker_order_id
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE s.stock_code = ? AND (st.id IS NULL OR st.status <> 'CANCELLED')
                ORDER BY t.created_at DESC, t.id DESC LIMIT 10
                """, (rs, row) -> new PublicTrade(rs.getString("side"), rs.getInt("quantity"),
                rs.getLong("price"), rs.getString("order_type"), databaseInstant(rs.getTimestamp("created_at")).toString()),
                code.toUpperCase(Locale.ROOT));
    }

    public synchronized List<PricePoint> priceHistory(String code) {
        return priceHistory(code, "24h");
    }

    /**
     * Returns the recorded price points inside one of the public chart windows.
     * The range is deliberately allow-listed so a client cannot inject SQL
     * interval fragments. A generous point cap keeps a week view lightweight;
     * the clients down-sample only when the canvas width requires it.
     */
    public synchronized List<PricePoint> priceHistory(String code, String range) {
        Instant cutoff = Instant.now().minus(historyWindow(range));
        List<PricePoint> points = jdbc.query("""
                SELECT h.price, h.recorded_at
                FROM stock_price_history h JOIN stocks s ON s.id = h.stock_id
                WHERE s.stock_code = ? AND h.recorded_at >= ?
                ORDER BY h.recorded_at DESC, h.id DESC
                LIMIT 2000
                """, (rs, row) -> new PricePoint(
                rs.getLong("price"),
                databaseInstant(rs.getTimestamp("recorded_at")).toString()),
                code.toUpperCase(Locale.ROOT), Timestamp.from(cutoff));
        Collections.reverse(points);
        return points;
    }

    /** Returns a bounded OHLC series for the chart ranges shown by the client. */
    public synchronized List<ChartCandle> chartHistory(String code, String range) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (findStock(normalized) == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        Duration window = chartWindow(range);
        long bucketSeconds = chartBucketSeconds(range);
        Instant cutoff = Instant.now().minus(window);
        return jdbc.query("""
                WITH bucketed AS (
                  SELECT h.id, h.price, h.recorded_at,
                         FLOOR(UNIX_TIMESTAMP(h.recorded_at) / ?) AS bucket,
                         ROW_NUMBER() OVER (
                           PARTITION BY FLOOR(UNIX_TIMESTAMP(h.recorded_at) / ?)
                           ORDER BY h.recorded_at ASC, h.id ASC
                         ) AS open_rank,
                         ROW_NUMBER() OVER (
                           PARTITION BY FLOOR(UNIX_TIMESTAMP(h.recorded_at) / ?)
                           ORDER BY h.recorded_at DESC, h.id DESC
                         ) AS close_rank
                  FROM stock_price_history h
                  JOIN stocks s ON s.id = h.stock_id
                  WHERE s.stock_code = ? AND h.recorded_at >= ?
                )
                SELECT bucket * ? AS bucket_at,
                       MAX(CASE WHEN open_rank = 1 THEN price END) AS open_price,
                       MAX(price) AS high_price,
                       MIN(price) AS low_price,
                       MAX(CASE WHEN close_rank = 1 THEN price END) AS close_price,
                       COUNT(*) AS point_count
                FROM bucketed
                GROUP BY bucket
                ORDER BY bucket ASC
                LIMIT 2000
                """, (rs, row) -> new ChartCandle(
                Instant.ofEpochSecond(rs.getLong("bucket_at")).toString(),
                rs.getLong("open_price"),
                rs.getLong("high_price"),
                rs.getLong("low_price"),
                rs.getLong("close_price"),
                rs.getLong("point_count")),
                bucketSeconds, bucketSeconds, bucketSeconds, normalized,
                Timestamp.from(cutoff), bucketSeconds);
    }

    private Duration chartWindow(String range) {
        return switch (range == null ? "1d" : range.trim().toLowerCase(Locale.ROOT)) {
            case "1h" -> Duration.ofHours(1);
            case "6h" -> Duration.ofHours(6);
            case "12h" -> Duration.ofHours(12);
            case "1d" -> Duration.ofDays(1);
            case "1w" -> Duration.ofDays(7);
            case "1m" -> Duration.ofDays(30);
            case "1y" -> Duration.ofDays(365);
            default -> Duration.ofDays(1);
        };
    }

    private long chartBucketSeconds(String range) {
        return switch (range == null ? "1d" : range.trim().toLowerCase(Locale.ROOT)) {
            case "1h" -> 60;
            case "6h" -> 300;
            case "12h" -> 600;
            case "1d" -> 1800;
            case "1w" -> 7200;
            case "1m" -> 86400;
            case "1y" -> 604800;
            default -> 1800;
        };
    }

    private Duration historyWindow(String range) {
        return switch (range == null ? "24h" : range.trim().toLowerCase(Locale.ROOT)) {
            case "5m" -> Duration.ofMinutes(5);
            case "10m" -> Duration.ofMinutes(10);
            case "30m" -> Duration.ofMinutes(30);
            case "1h" -> Duration.ofHours(1);
            case "6h" -> Duration.ofHours(6);
            case "12h" -> Duration.ofHours(12);
            case "7d", "1w" -> Duration.ofDays(7);
            case "24h" -> Duration.ofHours(24);
            default -> Duration.ofHours(24);
        };
    }

    /**
     * Returns a transparent, public summary of the inputs that have shaped a
     * stock recently. This deliberately uses existing news, trade, and order
     * tables so the explanation is derived from the same data as the price.
     */
    public synchronized PriceDrivers priceDrivers(String code) {
        settleDuePayments();
        expireOrders();
        String normalized = code.toUpperCase(Locale.ROOT);
        Stock stock = findStock(normalized);
        if (stock == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        long id = stockId(normalized);
        Double impact = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.impact * GREATEST(0, LEAST(1,
                    1 - TIMESTAMPDIFF(SECOND, COALESCE(e.published_at, e.created_at), CURRENT_TIMESTAMP) / 86400.0))), 0)
                FROM market_events e
                WHERE e.stock_id = ? AND e.event_type = 'NEWS'
                  AND COALESCE(e.published_at, e.created_at) >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                """, Double.class, id);
        Integer newsCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM market_events
                WHERE stock_id = ? AND event_type = 'NEWS'
                  AND COALESCE(published_at, created_at) >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                """, Integer.class, id);
        VolumeBreakdown volumes = jdbc.queryForObject("""
                SELECT
                  COALESCE(SUM(CASE WHEN buyer.password_hash <> 'BOT' THEN t.quantity ELSE 0 END), 0) AS user_buy,
                  COALESCE(SUM(CASE WHEN seller.password_hash <> 'BOT' THEN t.quantity ELSE 0 END), 0) AS user_sell,
                  COALESCE(SUM(CASE WHEN buyer.password_hash = 'BOT' THEN t.quantity ELSE 0 END), 0) AS bot_buy,
                  COALESCE(SUM(CASE WHEN seller.password_hash = 'BOT' THEN t.quantity ELSE 0 END), 0) AS bot_sell
                FROM trades t
                JOIN users buyer ON buyer.id = t.buyer_id
                JOIN users seller ON seller.id = t.seller_id
                WHERE t.stock_id = ?
                  AND t.created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                """, (rs, row) -> new VolumeBreakdown(rs.getLong("user_buy"), rs.getLong("user_sell"),
                rs.getLong("bot_buy"), rs.getLong("bot_sell")), id);
        VolumeBreakdown openOrders = jdbc.queryForObject("""
                SELECT
                  COALESCE(SUM(CASE WHEN side = 'BUY' THEN remaining_quantity ELSE 0 END), 0) AS open_buy,
                  COALESCE(SUM(CASE WHEN side = 'SELL' THEN remaining_quantity ELSE 0 END), 0) AS open_sell
                FROM orders
                WHERE stock_id = ? AND status = 'OPEN'
                """, (rs, row) -> new VolumeBreakdown(rs.getLong("open_buy"), rs.getLong("open_sell"), 0, 0), id);
        Timestamp latestNews = jdbc.queryForObject("""
                SELECT MAX(COALESCE(published_at, created_at)) FROM market_events
                WHERE stock_id = ? AND event_type = 'NEWS'
                """, Timestamp.class, id);
        Timestamp latestTrade = jdbc.queryForObject("SELECT MAX(created_at) FROM trades WHERE stock_id = ?", Timestamp.class, id);
        double newsImpact = Math.max(-10.0, Math.min(10.0, impact == null ? 0.0 : impact));
        long userNet = volumes.userBuy() - volumes.userSell();
        long botNet = volumes.botBuy() - volumes.botSell();
        long openNet = openOrders.userBuy() - openOrders.userSell();
        return new PriceDrivers(normalized, stock.price(), previousPrice(normalized), stock.changePercent(),
                newsImpact, newsCount == null ? 0 : newsCount, volumes.userBuy(), volumes.userSell(),
                volumes.botBuy(), volumes.botSell(), openOrders.userBuy(), openOrders.userSell(),
                latestNews == null ? null : databaseInstant(latestNews).toString(),
                latestTrade == null ? null : databaseInstant(latestTrade).toString(),
                priceDriversReason(stock.changePercent(), newsImpact, userNet, botNet, openNet));
    }

    private long previousPrice(String code) {
        Long previous = jdbc.queryForObject("SELECT previous_price FROM stocks WHERE stock_code = ?", Long.class, code);
        return previous == null ? 0 : previous;
    }

    private String priceDriversReason(double changePercent, double newsImpact, long userNet,
                                      long botNet, long openNet) {
        boolean newsUp = newsImpact > 0.2;
        boolean newsDown = newsImpact < -0.2;
        boolean flowUp = userNet > 0 || botNet > 0 || openNet > 0;
        boolean flowDown = userNet < 0 || botNet < 0 || openNet < 0;
        if (changePercent > 0 && newsUp && flowUp) return "최근 뉴스와 매수세가 함께 반영되어 가격이 상승했습니다.";
        if (changePercent < 0 && newsDown && flowDown) return "최근 뉴스와 매도세가 함께 반영되어 가격이 하락했습니다.";
        if (changePercent > 0 && newsDown && flowUp) return "하락 방향 뉴스가 있었지만 매수세가 우세해 가격이 상승했습니다.";
        if (changePercent < 0 && newsUp && flowDown) return "상승 방향 뉴스가 있었지만 매도세가 우세해 가격이 하락했습니다.";
        if (changePercent > 0 && flowUp) return "매수세가 매도세보다 커 가격이 상승했습니다.";
        if (changePercent < 0 && flowDown) return "매도세가 매수세보다 커 가격이 하락했습니다.";
        if (changePercent > 0 && newsUp) return "최근 뉴스 흐름이 상승 방향으로 반영되어 가격이 올랐습니다.";
        if (changePercent < 0 && newsDown) return "최근 뉴스 흐름이 하락 방향으로 반영되어 가격이 내렸습니다.";
        return "최근 뉴스와 거래 흐름이 뚜렷한 한 방향으로 모이지 않았습니다.";
    }

    public synchronized List<DailyCandle> dailySummaries(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (findStock(normalized) == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        seedDailySummaries();
        return jdbc.query("""
                SELECT s.stock_code, d.trading_date, d.open_price, d.close_price, d.total_volume
                FROM daily_market_summaries d JOIN stocks s ON s.id = d.stock_id
                WHERE s.stock_code = ?
                ORDER BY d.trading_date DESC
                LIMIT 90
                """, (rs, row) -> new DailyCandle(rs.getString("stock_code"),
                rs.getDate("trading_date").toLocalDate().toString(), rs.getLong("open_price"),
                rs.getLong("close_price"), rs.getLong("total_volume")), normalized);
    }

    public synchronized OrderBook orderBook(String code) {
        settleDuePayments();
        expireOrders();
        String normalized = code.toUpperCase(Locale.ROOT);
        if (findStock(normalized) == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        long id = stockId(normalized);
        List<OrderBookLevel> bids = jdbc.query("""
                SELECT price, SUM(remaining_quantity) quantity, COUNT(*) order_count FROM orders
                WHERE stock_id = ? AND side = 'BUY' AND status = 'OPEN' GROUP BY price ORDER BY price DESC LIMIT 10
                """, (rs, row) -> new OrderBookLevel(rs.getLong("price"), rs.getInt("quantity"), rs.getInt("order_count")), id);
        List<OrderBookLevel> asks = jdbc.query("""
                SELECT price, SUM(remaining_quantity) quantity, COUNT(*) order_count FROM orders
                WHERE stock_id = ? AND side = 'SELL' AND status = 'OPEN' GROUP BY price ORDER BY price ASC LIMIT 10
                """, (rs, row) -> new OrderBookLevel(rs.getLong("price"), rs.getInt("quantity"), rs.getInt("order_count")), id);
        return new OrderBook(normalized, bids, asks);
    }

    public synchronized MarketSnapshot snapshot() {
        return new MarketSnapshot(stocks(), portfolioUnsafe(demoUserId), marketEvents());
    }

    @Transactional
    public synchronized OrderResult order(OrderRequest request, long userId) {
        acquireMarketLock();
        settleDuePayments();
        expireOrders();
        advanceTradingProtections();
        String code = request.stockCode().toUpperCase(Locale.ROOT);
        String side = request.side().toUpperCase(Locale.ROOT);
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new IllegalArgumentException("주문 구분은 BUY 또는 SELL이어야 합니다.");
        }

        Stock stock = findStock(code);
        if (stock == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        String orderType = request.orderType() == null || request.orderType().isBlank() ? "MARKET" : request.orderType().toUpperCase(Locale.ROOT);
        if (!"MARKET".equals(orderType) && !"LIMIT".equals(orderType)) throw new IllegalArgumentException("주문 유형은 MARKET 또는 LIMIT이어야 합니다.");
        protection.validateOrder(code, orderType);
        long orderPrice = "LIMIT".equals(orderType) ? (request.price() == null ? 0 : request.price()) : stock.price();
        if ("LIMIT".equals(orderType) && orderPrice <= 0) throw new IllegalArgumentException("지정가를 입력해 주세요.");
        if ("LIMIT".equals(orderType) && orderPrice % tickSize(orderPrice) != 0)
            throw new IllegalArgumentException("호가 단위(" + tickSize(orderPrice) + "원)에 맞는 가격을 입력해 주세요.");
        if ("LIMIT".equals(orderType)) {
            PriceBand dailyBand = dailyPriceBand(code);
            if (!dailyBand.contains(orderPrice)) {
                throw new IllegalArgumentException("지정가는 오늘의 가격제한폭(" + dailyBand.lowerPrice()
                        + "원 ~ " + dailyBand.upperPrice() + "원) 안에서 입력해 주세요.");
            }
        }
        int requestedQuantity = request.quantity();
        long amount;
        try {
            amount = Math.multiplyExact(orderPrice, requestedQuantity);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("주문 금액이 너무 큽니다.");
        }
        if (amount <= 0) throw new IllegalArgumentException("주문 금액이 올바르지 않습니다.");
        enforceOrderLimits(userId, stockId(code), side, orderType, requestedQuantity, "LIMIT".equals(orderType) ? orderPrice : 0);
        long estimatedFee = feeFor(amount);
        long requiredCash;
        try {
            requiredCash = Math.addExact(amount, estimatedFee);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("주문 금액이 너무 큽니다.");
        }
        long stockId = stockId(code);
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
        if ("BUY".equals(side) && "MARKET".equals(orderType) && cash < requiredCash) throw new IllegalArgumentException("보유 현금이 부족합니다. (수수료 포함)");
        if ("BUY".equals(side) && "LIMIT".equals(orderType) && cash < requiredCash) throw new IllegalArgumentException("지정가 주문에 필요한 현금이 부족합니다. (수수료 포함)");
        if ("SELL".equals(side) && availableQuantity(userId, stockId) < requestedQuantity) throw new IllegalArgumentException("보유 수량이 부족합니다.");

        long reservedCash = "BUY".equals(side) && "LIMIT".equals(orderType) ? requiredCash : 0;
        int reservedQuantity = "SELL".equals(side) && "LIMIT".equals(orderType) ? requestedQuantity : 0;
        if (reservedCash > 0) jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ?", reservedCash, userId);
        String insertSql = """
                INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, remaining_quantity,
                                    reserved_cash, reserved_quantity, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 24 HOUR))
                """;
        if ("MARKET".equals(orderType)) {
            insertSql = """
                    INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, remaining_quantity,
                                        reserved_cash, reserved_quantity, status, expires_at)
                    VALUES (?, ?, ?, ?, NULL, ?, ?, 0, 0, 'OPEN', NULL)
                    """;
        }
        if ("MARKET".equals(orderType)) {
            jdbc.update(insertSql, userId, stockId, side, orderType, requestedQuantity, requestedQuantity);
        } else {
            jdbc.update(insertSql, userId, stockId, side, orderType, orderPrice, requestedQuantity,
                    requestedQuantity, reservedCash, reservedQuantity);
        }
        long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        MatchSummary matched = matchOrders(code);
        if ("MARKET".equals(orderType)) cancelRemainingMarket(orderId);
        OrderState afterMatch = findOrder(orderId);
        int executedQuantity = requestedQuantity - afterMatch.remainingQuantity();
        if (matched.hasTrades()) moveToPrice(code, matched.lastTradePrice(), matched.totalQuantity());
        MarketSnapshot snapshot = snapshot();
        events.publishEvent(new MarketChangedEvent(snapshot));
        long executedPrice = afterMatch.price() > 0 ? afterMatch.price() : orderPrice;
        ExecutionSummary execution = executionSummary(orderId, side);
        TradingRestriction restriction = protection.restriction(code);
        String message = restriction != null && "OPEN".equals(afterMatch.status())
                ? restriction.label() + " 단일가 주문 접수: 종료 시 모인 주문을 한 가격으로 체결합니다."
                : restriction != null && "MARKET".equals(orderType)
                ? restriction.label() + " 발동으로 미체결 시장가 잔량을 취소했습니다."
                : orderMessage(afterMatch.status());
        return new OrderResult(message, code, side, requestedQuantity, executedPrice,
                afterMatch.status(), portfolioUnsafe(userId), execution.fee(), execution.status(), execution.settlementAt());
    }

    @Scheduled(fixedRate = 3_000)
    @Transactional
    public synchronized void maintainMarket() {
        acquireMarketLock();
        settleDuePayments();
        expireOrders();
        advanceTradingProtections();
        for (String code : botStockCodes()) applyNewsPriceDrift(code, recentNewsBias(code));
        events.publishEvent(new MarketChangedEvent(snapshot()));
    }

    public synchronized List<String> botStockCodes() {
        return jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
    }

    public List<String> participantBotUsernames() {
        return TRADER_BOT_PROFILES.stream().map(TraderBotProfile::username).toList();
    }

    private void prepareBotAction() {
        acquireMarketLock();
        settleDuePayments();
        expireOrders();
        advanceTradingProtections();
        long tick = nextSimulationTick();
        tickRandom = new Random(simulationSeed ^ runtimeNoise ^ (tick * 0x9E3779B97F4A7C15L));
    }

    /** One side's liquidity bot acts on its own clock; matching stays transactional. */
    @Transactional
    public synchronized void liquidityBotAction(String code, String side) {
        prepareBotAction();
        if (!protection.marketStatus().open()) return;
        NewsBias newsBias = recentNewsBias(code);
        TradingRestriction restriction = protection.restriction(code);
        if (restriction != null) {
            if (restriction.limitOrdersAllowed()) createBotOrder(code, side, newsBias);
        } else if (tickRandom.nextDouble() < BOT_ACTIVE_ACTION_PROBABILITY) {
            if (tickRandom.nextDouble() < BOT_LIMIT_ORDER_PROBABILITY) createBotOrder(code, side, newsBias);
            boolean preferred = side.equals(burstSide(code, newsBias));
            if (tickRandom.nextDouble() < (preferred ? 0.45 : 0.20)) {
                boolean burst = tickRandom.nextDouble() < BOT_BURST_PROBABILITY;
                int burstQuantity = burst ? dynamicBotQuantity(code, side, newsBias, true) : 0;
                long orderId = createBotMarketOrder(code, side, newsBias, burstQuantity);
                MatchSummary matched = matchOrders(code);
                if (orderId > 0) cancelRemainingMarket(orderId);
                if (matched.hasTrades()) moveBotPrice(code, matched.vwap(), matched.totalQuantity(), newsBias.special());
            }
        }
        trimBotLiquidity(code);
        rebalanceBotLiquidity(code, newsBias);
        events.publishEvent(new MarketChangedEvent(snapshot()));
    }

    /** Participant accounts keep the ordinary user order path and finite funds. */
    @Transactional
    public synchronized void participantBotAction(String username) {
        prepareBotAction();
        if (!protection.marketStatus().open()) return;
        TraderBotProfile profile = TRADER_BOT_PROFILES.stream()
                .filter(item -> item.username().equals(username)).findFirst().orElseThrow();
        List<String> codes = botStockCodes();
        if (codes.isEmpty()) return;
        long userId = traderBotId(profile.username());
        String code = codes.get(tickRandom.nextInt(codes.size()));
        trimTraderOrders(userId);
        try {
            submitTraderOrder(profile, userId, code);
        } catch (IllegalArgumentException ignored) {
            // Insufficient funds, inventory or a trading restriction can mean waiting.
        }
        events.publishEvent(new MarketChangedEvent(snapshot()));
    }

    private boolean submitTraderOrder(TraderBotProfile profile, long userId, String code) {
        Stock stock = findStock(code);
        OrderBook book = orderBook(code);
        String side = traderSide(profile.style(), stock, book, userId);
        int quantity = traderQuantity(stock, userId, side);
        if (quantity <= 0) return false;
        boolean market = tickRandom.nextDouble() < 0.18;
        Long price = market ? null : traderLimitPrice(profile.style(), stock, book, side);
        order(new OrderRequest(code, side, quantity, market ? "MARKET" : "LIMIT", price), userId);
        return true;
    }

    private String traderSide(TraderStyle style, Stock stock, OrderBook book, long userId) {
        boolean hasShares = availableQuantity(userId, stockId(stock.code())) > 0;
        return switch (style) {
            case MOMENTUM -> stock.changePercent() >= 0 ? "BUY" : (hasShares ? "SELL" : "BUY");
            case CONTRARIAN -> stock.changePercent() >= 0 && hasShares ? "SELL" : "BUY";
            case VALUE -> tickRandom.nextDouble() < 0.58 && hasShares ? "SELL" : "BUY";
            case INTRADAY -> tickRandom.nextBoolean() && hasShares ? "SELL" : "BUY";
        };
    }

    private int traderQuantity(Stock stock, long userId, String side) {
        int requested = 5 + tickRandom.nextInt(TRADER_BOT_MAX_ORDER_QUANTITY - 4);
        if ("SELL".equals(side)) {
            return Math.min(requested, Math.max(0, availableQuantity(userId, stockId(stock.code()))));
        }
        long availableCash = availableCash(userId);
        long perShare = Math.max(1L, Math.round(stock.price() * (1.0 + TRADING_FEE_RATE)));
        return Math.min(requested, (int) Math.min(Integer.MAX_VALUE, availableCash / perShare));
    }

    private long traderLimitPrice(TraderStyle style, Stock stock, OrderBook book, String side) {
        long tick = tickSize(stock.price());
        boolean aggressive = tickRandom.nextDouble() < traderAggression(style);
        PriceBand dailyBand = dailyPriceBand(stock.code());
        if ("BUY".equals(side)) {
            long bestBid = book.bids().isEmpty() ? floorToTick(stock.price() - tick) : book.bids().get(0).price();
            if (aggressive) {
                // Some participants pay the ask (or one tick above it) so
                // buys are not always parked below the current market.
                long bestAsk = book.asks().isEmpty() ? nextTickPrice(stock.price()) : book.asks().get(0).price();
                long price = bestAsk + (tickRandom.nextBoolean() ? 0 : tick);
                return floorToTick(dailyBand.clamp(price));
            }
            long price = tickRandom.nextDouble() < 0.55 ? bestBid : stock.price() - tick * (1 + tickRandom.nextInt(3));
            return floorToTick(dailyBand.clamp(Math.max(tick, price)));
        }
        long bestAsk = book.asks().isEmpty() ? ceilToTick(stock.price() + tick) : book.asks().get(0).price();
        if (aggressive) {
            // Some participants hit the bid (or one tick below it), creating
            // realistic cheap sells instead of making every seller wait above
            // the market for a buyer.
            long bestBid = book.bids().isEmpty() ? previousTickPrice(stock.price()) : book.bids().get(0).price();
            long price = bestBid - (tickRandom.nextBoolean() ? 0 : tick);
            return ceilToTick(dailyBand.clamp(price));
        }
        long price = tickRandom.nextDouble() < 0.55 ? bestAsk : stock.price() + tick * (1 + tickRandom.nextInt(3));
        return ceilToTick(dailyBand.clamp(price));
    }

    private double traderAggression(TraderStyle style) {
        return switch (style) {
            case MOMENTUM -> 0.32;
            case CONTRARIAN -> 0.24;
            case VALUE -> 0.18;
            case INTRADAY -> 0.45;
        };
    }

    private void trimTraderOrders(long userId) {
        List<Long> openOrders = jdbc.query("""
                SELECT id FROM orders
                WHERE user_id = ? AND status = 'OPEN' AND order_type = 'LIMIT'
                ORDER BY created_at ASC, id ASC
                """, (rs, row) -> rs.getLong(1), userId);
        for (int index = 0; index < openOrders.size() - TRADER_BOT_MAX_OPEN_ORDERS; index++) {
            cancelOrderInternal(openOrders.get(index));
        }
    }

    private void createBotOrder(String code, String side, NewsBias newsBias) {
        createBotOrder(code, side, newsBias, null);
    }

    private void createBotOrder(String code, String side, NewsBias newsBias, Long preferredPrice) {
        long id = ensureBot(code, side);
        Stock stock = findStock(code);
        long price;
        if (preferredPrice != null) {
            price = botQuotePrice(botPriceBand(code), side, preferredPrice);
        } else {
            double offset = 0.002 + tickRandom.nextDouble() * 0.006;
            // 뉴스로 이동한 기준가 주변에 호가를 내고, 뉴스 방향의 수량을 조금 더 크게 준다.
            double fairPrice = stock.price() * (1 + newsBias.rate());
            double rawPrice = fairPrice * ("BUY".equals(side) ? 1 - offset : 1 + offset);
            price = "BUY".equals(side) ? floorToTick(rawPrice) : ceilToTick(rawPrice);
            price = botQuotePrice(botPriceBand(code), side, price);
            // Keep a visible ladder around the spread. Independent random quotes
            // still occur, but most replenishment fills the next legal tick beside
            // the current best LP quote instead of skipping 20~30 won levels.
            if (tickRandom.nextDouble() < 0.72) {
                price = adjacentBotQuotePrice(code, side, price);
                price = botQuotePrice(botPriceBand(code), side, price);
            }
        }
        long idStock = stockId(code);
        if (botPriceLevelOccupied(idStock, side, price)) return;
        ensureBotInventory(code, id);
        int quantity = dynamicBotQuantity(code, side, newsBias, false);
        if (quantity <= 0) return;
        if ("BUY".equals(side)) {
            long amount = price * quantity;
            long fee = feeFor(amount);
            long required = amount + fee;
            int updated = jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ? AND cash >= ?", required, id, required);
            if (updated == 0) return;
            jdbc.update("INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, remaining_quantity, reserved_cash, reserved_quantity, status, expires_at) VALUES (?, ?, ?, 'LIMIT', ?, ?, ?, ?, 0, 'OPEN', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL " + BOT_ORDER_LIFETIME_MINUTES + " MINUTE))", id, idStock, side, price, quantity, quantity, required);
        } else {
            jdbc.update("INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, remaining_quantity, reserved_cash, reserved_quantity, status, expires_at) VALUES (?, ?, ?, 'LIMIT', ?, ?, ?, 0, ?, 'OPEN', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL " + BOT_ORDER_LIFETIME_MINUTES + " MINUTE))", id, idStock, side, price, quantity, quantity, quantity);
        }
    }

    private long createBotMarketOrder(String code, String side, NewsBias newsBias) {
        return createBotMarketOrder(code, side, newsBias, 0);
    }

    private long createBotMarketOrder(String code, String side, NewsBias newsBias, int burstQuantity) {
        long id = ensureBot(code, side);
        long idStock = stockId(code);
        ensureBotInventory(code, id);
        int quantity = burstQuantity > 0 ? Math.min(burstQuantity, dynamicBotQuantity(code, side, newsBias, true))
                : dynamicBotQuantity(code, side, newsBias, false);
        if (quantity <= 0) return 0;
        jdbc.update("INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, remaining_quantity, reserved_cash, reserved_quantity, status, expires_at) VALUES (?, ?, ?, 'MARKET', NULL, ?, ?, 0, 0, 'OPEN', NULL)", id, idStock, side, quantity, quantity);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String burstSide(String code, NewsBias newsBias) {
        // Follow a strong headline direction most of the time, but retain a
        // small chance of the opposite side to avoid a deterministic tape.
        if (newsBias.rate() > 0.01) {
            if (botBandPosition(code) >= BOT_NEAR_LIMIT_POSITION)
                return tickRandom.nextDouble() < BOT_LIMIT_REVERSAL_PROBABILITY ? "SELL" : "BUY";
            return tickRandom.nextDouble() < 0.68 ? "BUY" : "SELL";
        }
        if (newsBias.rate() < -0.01) {
            if (botBandPosition(code) <= 1.0 - BOT_NEAR_LIMIT_POSITION)
                return tickRandom.nextDouble() < BOT_LIMIT_REVERSAL_PROBABILITY ? "BUY" : "SELL";
            return tickRandom.nextDouble() < 0.68 ? "SELL" : "BUY";
        }
        return tickRandom.nextBoolean() ? "BUY" : "SELL";
    }

    /** 0 is the bot lower band, 1 is the bot upper band. */
    private double botBandPosition(String code) {
        PriceBand band = botPriceBand(code);
        long span = band.upperPrice() - band.lowerPrice();
        if (span <= 0) return 0.5;
        return Math.max(0.0, Math.min(1.0,
                (findStock(code).price() - band.lowerPrice()) / (double) span));
    }

    /**
     * Order size follows one-hour market liquidity and the bot's actual
     * cash/holdings. There is no arbitrary fixed 3..64 order cap.
     */
    private int dynamicBotQuantity(String code, String side, NewsBias newsBias, boolean burst) {
        long stockId = stockId(code);
        Stock stock = findStock(code);
        Long recentVolume = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM trades
                WHERE stock_id = ? AND created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 HOUR)
                """, Long.class, stockId);
        long observedVolume = recentVolume == null ? 0L : Math.max(0L, recentVolume);
        double participation = burst ? BOT_BURST_VOLUME_PARTICIPATION : BOT_NORMAL_VOLUME_PARTICIPATION;
        long liquidityQuantity = observedVolume > 0
                ? Math.max(2L, Math.round(observedVolume * participation))
                : 6L + tickRandom.nextInt(15);
        double noise = 0.45 + tickRandom.nextDouble() * 1.10;
        long desired = Math.max(1L, Math.round(liquidityQuantity * noise));
        double directionalRate = "BUY".equals(side) ? newsBias.rate() : -newsBias.rate();
        double newsMultiplier = 1.0 + Math.max(-0.35, Math.min(0.45, directionalRate * BOT_NEWS_SIZE_SENSITIVITY));
        long orderCap = burst ? BOT_BURST_ORDER_MAX : BOT_NORMAL_ORDER_MAX;
        long requested = Math.min(orderCap, Math.max(1L, Math.round(desired * newsMultiplier)));
        long botId = ensureBot(code, side);
        if ("SELL".equals(side)) ensureBotInventory(code, botId);
        long available = "BUY".equals(side)
                ? availableCash(botId) / Math.max(1L, Math.round(stock.price() * (1.0 + TRADING_FEE_RATE)))
                : availableQuantity(botId, stockId);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, Math.min(requested, available)));
    }

    /**
     * Keep a synthetic market-maker inventory available for sell-side liquidity.
     *
     * Bot accounts are not real investors, so their inventory is a liquidity
     * budget rather than a finite user balance. Refill only the amount below
     * the target after a sell is executed; this preserves the constraint that
     * every sell is backed by holdings while preventing the ask side from
     * permanently disappearing after the initial seed is consumed.
     */
    private void ensureBotInventory(String code, long userId) {
        long id = stockId(code);
        List<HoldingState> holdings = jdbc.query("""
                SELECT quantity, settled_quantity, average_price, realized_profit_loss
                FROM portfolios WHERE user_id = ? AND stock_id = ? FOR UPDATE
                """, (rs, row) -> new HoldingState(
                rs.getInt("quantity"),
                rs.getInt("settled_quantity"),
                rs.getLong("average_price"),
                rs.getLong("realized_profit_loss")), userId, id);
        if (holdings.isEmpty()) {
            jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                    VALUES (?, ?, ?, ?, ?, 0)
                    """, userId, id, BOT_INITIAL_INVENTORY, BOT_INITIAL_INVENTORY, findStock(code).price());
            return;
        }

        HoldingState holding = holdings.get(0);
        int refill = BOT_INITIAL_INVENTORY - holding.quantity();
        if (refill <= 0) return;

        long refillPrice = findStock(code).price();
        int nextQuantity = holding.quantity() + refill;
        long nextAveragePrice = nextQuantity == 0
                ? refillPrice
                : Math.round(((double) holding.averagePrice() * holding.quantity()
                + (double) refillPrice * refill) / nextQuantity);
        jdbc.update("""
                UPDATE portfolios
                SET quantity = quantity + ?,
                    settled_quantity = COALESCE(settled_quantity, quantity) + ?,
                    average_price = ?
                WHERE user_id = ? AND stock_id = ?
                """, refill, refill, nextAveragePrice, userId, id);
    }

    /** Keep a one-tick spread at the bot band's edges so a bot never freezes
     * the market by posting both sides at the same boundary price. */
    private long botQuotePrice(PriceBand band, String side, long rounded) {
        if ("BUY".equals(side)) {
            long upper = Math.max(band.lowerPrice(), previousTickPrice(band.upperPrice()));
            return Math.max(band.lowerPrice(), Math.min(upper, rounded));
        }
        long lower = Math.min(band.upperPrice(), nextTickPrice(band.lowerPrice()));
        return Math.min(band.upperPrice(), Math.max(lower, rounded));
    }

    private void trimBotLiquidity(String code) {
        long stockId = stockId(code);
        for (String side : List.of("BUY", "SELL")) {
            List<BotOpenOrder> openOrders = jdbc.query("""
                    SELECT o.id, o.price FROM orders o JOIN users u ON u.id = o.user_id
                    WHERE o.stock_id = ? AND o.side = ? AND o.order_type = 'LIMIT'
                      AND o.status = 'OPEN' AND u.password_hash = 'BOT'
                    ORDER BY o.created_at DESC, o.id DESC
                    """, (rs, row) -> new BotOpenOrder(rs.getLong("id"), rs.getLong("price")), stockId, side);
            Set<Long> occupiedPrices = new HashSet<>();
            int kept = 0;
            for (BotOpenOrder order : openOrders) {
                if (kept >= MAX_BOT_OPEN_ORDERS_PER_SIDE || !occupiedPrices.add(order.price())) {
                    cancelOrderInternal(order.id());
                    continue;
                }
                kept++;
            }

            // Remove legacy bot orders that were created before per-order caps
            // existed. User orders are intentionally left untouched.
            List<Long> oversizedOrders = jdbc.query("""
                    SELECT o.id FROM orders o JOIN users u ON u.id = o.user_id
                    WHERE o.stock_id = ? AND o.side = ? AND o.order_type = 'LIMIT'
                      AND o.status = 'OPEN' AND u.password_hash = 'BOT'
                      AND o.remaining_quantity > ?
                    """, (rs, row) -> rs.getLong(1), stockId, side, BOT_BURST_ORDER_MAX);
            for (Long orderId : oversizedOrders) cancelOrderInternal(orderId);

            // Orders created before the shorter lifetime was introduced should
            // not remain in the book for a full day.
            List<Long> staleOrders = jdbc.query("""
                    SELECT o.id FROM orders o JOIN users u ON u.id = o.user_id
                    WHERE o.stock_id = ? AND o.order_type = 'LIMIT' AND o.status = 'OPEN'
                      AND u.password_hash = 'BOT'
                      AND o.created_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 20 MINUTE)
                    """, (rs, row) -> rs.getLong(1), stockId);
            for (Long orderId : staleOrders) cancelOrderInternal(orderId);
        }
    }

    /**
     * Keep the synthetic LP genuinely two-sided. BUY and SELL schedules are
     * intentionally independent, so a fill or an expired quote can otherwise
     * leave one side of the book empty until its next random turn. Refill the
     * missing side and correct severe quantity imbalance without touching user
     * orders or forcing the two sides to have identical depth.
     */
    private void rebalanceBotLiquidity(String code, NewsBias newsBias) {
        TradingRestriction restriction = protection.restriction(code);
        if (restriction != null && !restriction.limitOrdersAllowed()) return;
        ensureNearBotQuotes(code, newsBias);
        for (int attempt = 0; attempt < LP_REBALANCE_ATTEMPTS; attempt++) {
            LiquidityDepth buy = botLiquidityDepth(code, "BUY");
            LiquidityDepth sell = botLiquidityDepth(code, "SELL");
            boolean refillBuy = buy.orders() < LP_MIN_OPEN_ORDERS_PER_SIDE
                    || buy.quantity() < LP_MIN_OPEN_QUANTITY_PER_SIDE;
            boolean refillSell = sell.orders() < LP_MIN_OPEN_ORDERS_PER_SIDE
                    || sell.quantity() < LP_MIN_OPEN_QUANTITY_PER_SIDE;
            if (buy.quantity() > 0 && sell.quantity() > 0) {
                refillBuy |= buy.quantity() > Math.round(sell.quantity() * LP_MAX_SIDE_IMBALANCE);
                refillSell |= sell.quantity() > Math.round(buy.quantity() * LP_MAX_SIDE_IMBALANCE);
            }
            if (!refillBuy && !refillSell) return;
            if (refillBuy) createBotOrder(code, "BUY", newsBias);
            if (refillSell) createBotOrder(code, "SELL", newsBias);
        }
    }

    /**
     * A side can have plenty of total depth while still leaving a wide empty
     * area next to the spread. Fill only the missing near levels so old or
     * intentionally wider quotes do not hide the inside market.
     */
    private void ensureNearBotQuotes(String code, NewsBias newsBias) {
        long reference = findStock(code).price();
        long stockId = stockId(code);
        OrderBook book = orderBook(code);
        for (String side : List.of("BUY", "SELL")) {
            long target = floorToTick(reference);
            for (int level = 1; level <= LP_NEAR_QUOTE_LEVELS; level++) {
                target = "BUY".equals(side)
                        ? previousTickPrice(target)
                        : nextTickPrice(target);
                target = botQuotePrice(botPriceBand(code), side, target);
                if (botPriceLevelOccupied(stockId, side, target)
                        || quoteWouldCrossOpposite(book, side, target)) continue;
                createBotOrder(code, side, newsBias, target);
            }
        }
    }

    private boolean quoteWouldCrossOpposite(OrderBook book, String side, long price) {
        if ("BUY".equals(side)) {
            return !book.asks().isEmpty() && price >= book.asks().get(0).price();
        }
        return !book.bids().isEmpty() && price <= book.bids().get(0).price();
    }

    private LiquidityDepth botLiquidityDepth(String code, String side) {
        List<LiquidityDepth> rows = jdbc.query("""
                SELECT COUNT(*), COALESCE(SUM(o.remaining_quantity), 0)
                FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.stock_id = ? AND o.side = ? AND o.order_type = 'LIMIT'
                  AND o.status = 'OPEN' AND u.password_hash = 'BOT'
                """, (rs, row) -> new LiquidityDepth(rs.getInt(1), rs.getLong(2)), stockId(code), side);
        return rows.isEmpty() ? new LiquidityDepth(0, 0) : rows.get(0);
    }

    private long adjacentBotQuotePrice(String code, String side, long fallback) {
        long stockId = stockId(code);
        long reference = findStock(code).price();
        long referenceTick = tickSize(reference);
        List<Long> edge = jdbc.query("""
                SELECT o.price
                FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.stock_id = ? AND o.side = ? AND o.order_type = 'LIMIT'
                  AND o.status = 'OPEN' AND u.password_hash = 'BOT'
                ORDER BY o.price """ + ("BUY".equals(side) ? "DESC" : "ASC") + " LIMIT 1",
                (rs, row) -> rs.getLong(1), stockId, side);
        long candidate;
        if (edge.isEmpty() || Math.abs(edge.get(0) - reference) > referenceTick * LP_MAX_NEAR_QUOTE_DISTANCE_TICKS) {
            // A moving market can leave an old quote far from the new price.
            // Start the replenishment at the current inside level so the
            // spread does not remain wide until the old order expires.
            candidate = "BUY".equals(side) ? floorToTick(reference) : nextTickPrice(floorToTick(reference));
        } else {
            candidate = "BUY".equals(side) ? previousTickPrice(edge.get(0)) : nextTickPrice(edge.get(0));
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            if (!botPriceLevelOccupied(stockId, side, candidate)) return candidate;
            candidate = "BUY".equals(side) ? previousTickPrice(candidate) : nextTickPrice(candidate);
        }
        return fallback;
    }

    private boolean botPriceLevelOccupied(long stockId, String side, long price) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.stock_id = ? AND o.side = ? AND o.price = ?
                  AND o.order_type = 'LIMIT' AND o.status = 'OPEN' AND u.password_hash = 'BOT'
                """, Integer.class, stockId, side, price);
        return count != null && count > 0;
    }

    private long traderBotId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ? AND password_hash = ?",
                Long.class, username, TRADER_BOT_PASSWORD);
    }

    private long ensureBot(String code, String side) {
        String username = "bot_" + code + "_" + side.toLowerCase(Locale.ROOT);
        jdbc.update("INSERT IGNORE INTO users (username, password_hash, nickname, cash) VALUES (?, 'BOT', ?, 1000000000)", username, "봇 " + code + " " + side);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private void advanceTradingProtections() {
        protection.refreshDay();
        protection.advanceMarketPhase();
        if (protection.marketAuctionDue()) {
            List<String> codes = jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
            for (String code : codes) {
                closeSinglePriceAuction(code);
                protection.finishAuction(code, findStock(code).price());
            }
            protection.finishMarketAuction();
        }
        for (String code : protection.dueViAuctions()) {
            if (protection.marketStatus().restriction() != null) break;
            closeSinglePriceAuction(code);
            protection.finishAuction(code, findStock(code).price());
        }
        protection.observeMarket();
    }

    /** Maximise executable volume, then minimise imbalance and distance from the quote. */
    private void closeSinglePriceAuction(String code) {
        long stockId = stockId(code);
        long reference = findStock(code).price();
        PriceBand daily = dailyPriceBand(code);
        PriceBand bots = botPriceBand(code);
        List<MatchRow> orders = jdbc.query("""
                SELECT o.id, o.user_id, o.price, o.quantity, o.remaining_quantity, o.order_type,
                       o.created_at, o.reserved_cash, o.reserved_quantity, o.side,
                       (u.password_hash = 'BOT') AS is_bot
                FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.stock_id = ? AND o.status = 'OPEN' AND o.order_type = 'LIMIT'
                  AND o.remaining_quantity > 0 ORDER BY o.created_at, o.id
                """, this::auctionRow, stockId);
        java.util.SortedSet<Long> candidates = new java.util.TreeSet<>();
        candidates.add(floorToTick(reference));
        for (MatchRow order : orders) {
            if (daily.contains(order.price()) && order.price() % tickSize(order.price()) == 0) candidates.add(order.price());
        }
        long price = reference;
        long bestVolume = 0;
        long bestImbalance = Long.MAX_VALUE;
        for (long candidate : candidates) {
            if (!daily.contains(candidate)) continue;
            long buys = 0;
            long sells = 0;
            for (MatchRow order : orders) {
                if (order.bot() && !bots.contains(candidate)) continue;
                if ("BUY".equals(order.side()) && order.price() >= candidate) buys += order.remainingQuantity();
                if ("SELL".equals(order.side()) && order.price() <= candidate) sells += order.remainingQuantity();
            }
            long volume = Math.min(buys, sells);
            long imbalance = Math.abs(buys - sells);
            if (volume > bestVolume || (volume == bestVolume && volume > 0 &&
                    (imbalance < bestImbalance || (imbalance == bestImbalance
                            && Math.abs(candidate - reference) < Math.abs(price - reference))))) {
                price = candidate;
                bestVolume = volume;
                bestImbalance = imbalance;
            }
        }
        if (bestVolume == 0) return;
        long volume = 0;
        while (true) {
            MatchRow buy = topAuctionOrder(stockId, "BUY", price, bots.contains(price));
            MatchRow sell = topAuctionOrder(stockId, "SELL", price, bots.contains(price));
            if (buy == null || sell == null) break;
            int quantity = Math.min(buy.remainingQuantity(), sell.remainingQuantity());
            if (!canSettle(buy, sell, quantity, price, stockId)) {
                if (buy.reservedCash() == 0 && availableCash(buy.userId()) < price * quantity + feeFor(price * quantity)) cancelOrderInternal(buy.id());
                else cancelOrderInternal(sell.id());
                continue;
            }
            MatchRow maker = earlier(buy, sell) ? buy : sell;
            MatchRow taker = maker.id() == buy.id() ? sell : buy;
            settleTrade(stockId, buy, sell, maker, taker, quantity, price);
            volume += quantity;
        }
        if (volume > 0) moveToPrice(code, price, volume);
    }

    private MatchRow topAuctionOrder(long stockId, String side, long price, boolean botsAllowed) {
        String comparison = "BUY".equals(side) ? ">=" : "<=";
        String priority = "BUY".equals(side) ? "DESC" : "ASC";
        List<MatchRow> rows = jdbc.query("""
                SELECT o.id, o.user_id, o.price, o.quantity, o.remaining_quantity, o.order_type,
                       o.created_at, o.reserved_cash, o.reserved_quantity, o.side,
                       (u.password_hash = 'BOT') AS is_bot
                FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.stock_id = ? AND o.side = ? AND o.status = 'OPEN'
                  AND o.order_type = 'LIMIT' AND o.remaining_quantity > 0 AND o.price
                """ + comparison + " ? " + (botsAllowed ? "" : "AND u.password_hash <> 'BOT' ")
                + "ORDER BY o.price " + priority + ", o.created_at, o.id LIMIT 1",
                this::auctionRow, stockId, side, price);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MatchRow auctionRow(ResultSet rs, int row) throws SQLException {
        return new MatchRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6), databaseInstant(rs.getTimestamp(7)), rs.getLong(8), rs.getInt(9), rs.getString(10), rs.getBoolean(11));
    }

    private MatchSummary matchOrders(String code) {
        if (!protection.continuous(code)) return MatchSummary.empty();
        long id = stockId(code);
        MatchSummary summary = MatchSummary.empty();
        PriceBand dailyBand = dailyPriceBand(code);
        PriceBand botBand = botPriceBand(code);
        while (true) {
            MatchRow buy = topOrder(id, "BUY");
            MatchRow sell = topOrder(id, "SELL");
            if (buy == null || sell == null) return summary;
            boolean buyMarket = "MARKET".equals(buy.orderType());
            boolean sellMarket = "MARKET".equals(sell.orderType());
            if (!buyMarket && !sellMarket && buy.price() < sell.price()) return summary;

            int quantity = Math.min(buy.remainingQuantity(), sell.remainingQuantity());
            MatchRow maker;
            MatchRow taker;
            long tradePrice;
            if (buyMarket && sellMarket) {
                maker = earlier(buy, sell) ? buy : sell;
                taker = maker.id() == buy.id() ? sell : buy;
                tradePrice = findStock(code).price();
            } else if (buyMarket) {
                maker = sell;
                taker = buy;
                tradePrice = sell.price();
            } else if (sellMarket) {
                maker = buy;
                taker = sell;
                tradePrice = buy.price();
            } else if (earlier(buy, sell)) {
                maker = buy;
                taker = sell;
                tradePrice = buy.price();
            } else {
                maker = sell;
                taker = buy;
                tradePrice = sell.price();
            }

            // 시장가는 372.ro처럼 현재가 기준 ±10%의 반대 호가까지만 순서대로 훑는다.
            // 범위를 벗어난 지정가와는 체결하지 않아 한 번의 시장가 주문이 시세를 급격히 건너뛰지 않는다.
            PriceBand marketBand = marketExecutionBand(findStock(code).price());
            if (buyMarket && !marketBand.contains(tradePrice)) {
                if (buy.bot()) cancelOrderInternal(buy.id());
                else if (sell.bot()) cancelOrderInternal(sell.id());
                else return summary;
                continue;
            }
            if (sellMarket && !marketBand.contains(tradePrice)) {
                if (sell.bot()) cancelOrderInternal(sell.id());
                else if (buy.bot()) cancelOrderInternal(buy.id());
                else return summary;
                continue;
            }

            // Automated liquidity is deliberately narrower than the market's
            // legal daily range. Any stale or user-provided quote outside the
            // bot band must not let a bot create an upper/lower-limit trade.
            if ((buy.bot() || sell.bot()) && !botBand.contains(tradePrice)) {
                if (buy.bot()) cancelOrderInternal(buy.id());
                if (sell.bot()) cancelOrderInternal(sell.id());
                continue;
            }
            // New orders are validated before insertion, but this final guard
            // also protects matching from legacy rows already stored in MySQL.
            if (!dailyBand.contains(tradePrice)) return summary;

            if (!canSettle(buy, sell, quantity, tradePrice, id)) {
                if (buy.reservedCash() == 0 && availableCash(buy.userId()) < tradePrice * quantity + feeFor(tradePrice * quantity)) cancelOrderInternal(buy.id());
                if (sell.reservedQuantity() == 0 && availableQuantity(sell.userId(), id) < quantity) cancelOrderInternal(sell.id());
                continue;
            }
            if (!protection.beforeTrade(code, tradePrice)) return summary;
            settleTrade(id, buy, sell, maker, taker, quantity, tradePrice);
            summary = summary.add(quantity, tradePrice, taker.side());
        }
    }

    private MatchRow topOrder(long stockId, String side) {
        String priceOrder = "BUY".equals(side) ? "o.price DESC" : "o.price ASC";
        String sql = "SELECT o.id, o.user_id, COALESCE(o.price, 0), o.quantity, o.remaining_quantity, o.order_type, o.created_at, COALESCE(o.reserved_cash, 0), COALESCE(o.reserved_quantity, 0), o.side, (u.password_hash = 'BOT') AS is_bot "
                + "FROM orders o JOIN users u ON u.id = o.user_id WHERE o.stock_id = ? AND o.side = ? AND o.status = 'OPEN' AND o.remaining_quantity > 0 "
                + "ORDER BY CASE WHEN o.order_type = 'MARKET' THEN 1 ELSE 0 END DESC, " + priceOrder + ", o.created_at ASC, o.id ASC LIMIT 1";
        List<MatchRow> rows = jdbc.query(sql, (rs, row) -> new MatchRow(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6), databaseInstant(rs.getTimestamp(7)), rs.getLong(8), rs.getInt(9), rs.getString(10),
                rs.getBoolean(11)), stockId, side);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean canSettle(MatchRow buy, MatchRow sell, int quantity, long tradePrice, long stockId) {
        long grossAmount = tradePrice * quantity;
        long requiredCash = grossAmount + feeFor(grossAmount);
        if (buy.reservedCash() == 0 && availableCash(buy.userId()) < requiredCash) return false;
        return sell.reservedQuantity() > 0 || availableQuantity(sell.userId(), stockId) >= quantity;
    }

    private void settleTrade(long stockId, MatchRow buy, MatchRow sell, MatchRow maker, MatchRow taker, int quantity, long tradePrice) {
        long amount = tradePrice * quantity;
        long buyerFee = feeFor(amount);
        long sellerFee = feeFor(amount);
        HoldingState buyerBefore = holdingState(stockId, buy.userId());
        HoldingState sellerBefore = holdingState(stockId, sell.userId());
        if (buy.reservedCash() > 0) {
            long reservedGross = buy.price() * quantity;
            long reservedChunk = reservedGross + feeFor(reservedGross);
            jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reservedChunk - amount - buyerFee, buy.userId());
        } else {
            jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ?", amount + buyerFee, buy.userId());
        }
        adjustQuantity(stockId, buy.userId(), quantity, tradePrice, buyerFee, true);
        adjustQuantity(stockId, sell.userId(), quantity, tradePrice, sellerFee, false);
        // GameStock settles fills immediately; the seller receives cash and
        // the buyer's shares become settled in the same transaction.
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", amount - sellerFee, sell.userId());
        jdbc.update("""
                INSERT INTO trades (stock_id, buy_order_id, sell_order_id, buyer_id, seller_id,
                                    maker_order_id, taker_order_id, aggressor_side, quantity, price,
                                    buyer_fee, seller_fee, fee)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stockId, buy.id(), sell.id(), buy.userId(), sell.userId(), maker.id(), taker.id(),
                taker.side(), quantity, tradePrice, buyerFee, sellerFee, buyerFee + sellerFee);
        long tradeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO settlements (trade_id, buyer_id, seller_id, stock_id, quantity, gross_amount,
                                         buyer_fee, seller_fee, settlement_at, status,
                                         buyer_quantity_before, buyer_settled_quantity_before,
                                         buyer_average_price_before, buyer_realized_profit_loss_before,
                                         seller_quantity_before, seller_settled_quantity_before,
                                         seller_average_price_before, seller_realized_profit_loss_before)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'SETTLED',
                        ?, ?, ?, ?, ?, ?, ?, ?)
                """, tradeId, buy.userId(), sell.userId(), stockId, quantity, amount, buyerFee, sellerFee,
                buyerBefore.quantity(), buyerBefore.settledQuantity(), buyerBefore.averagePrice(),
                buyerBefore.realizedProfitLoss(), sellerBefore.quantity(), sellerBefore.settledQuantity(),
                sellerBefore.averagePrice(), sellerBefore.realizedProfitLoss());
        jdbc.update("UPDATE settlements SET settled_at = CURRENT_TIMESTAMP WHERE trade_id = ?", tradeId);
        updateMatchedOrder(buy, quantity, tradePrice);
        updateMatchedOrder(sell, quantity, tradePrice);
        protection.recordTrade(stockId, tradePrice);
    }

    private void updateMatchedOrder(MatchRow order, int filledQuantity, long tradePrice) {
        int remaining = order.remainingQuantity() - filledQuantity;
        int filledBefore = order.quantity() - order.remainingQuantity();
        long nextPrice = "MARKET".equals(order.orderType())
                ? weightedAverage(order.price(), filledBefore, tradePrice, filledQuantity)
                : order.price();
        long reservedCash = order.reservedCash();
        if (reservedCash > 0 && "BUY".equals(order.side())) {
            // Recompute the reservation from the remaining quantity so fee
            // rounding does not accumulate a one-won drift over many fills.
            long remainingGross = order.price() * (long) remaining;
            reservedCash = remaining == 0 ? 0 : remainingGross + feeFor(remainingGross);
        }
        int reservedQuantity = order.reservedQuantity();
        if (reservedQuantity > 0 && "SELL".equals(order.side())) reservedQuantity = Math.max(0, reservedQuantity - filledQuantity);
        jdbc.update("UPDATE orders SET price = ?, remaining_quantity = ?, reserved_cash = ?, reserved_quantity = ?, status = ? WHERE id = ?",
                nextPrice, remaining, reservedCash, reservedQuantity, remaining == 0 ? "FILLED" : "OPEN", order.id());
    }

    private long weightedAverage(long previousAverage, int filledBefore, long tradePrice, int filledQuantity) {
        int filled = filledBefore + filledQuantity;
        return filled == 0 ? 0 : Math.round(((double) previousAverage * filledBefore + (double) tradePrice * filledQuantity) / filled);
    }

    private void cancelRemainingMarket(long orderId) {
        OrderState order = findOrder(orderId);
        if (order.remainingQuantity() <= 0) return;
        String status = order.quantity() == order.remainingQuantity() ? "CANCELLED" : "PARTIAL";
        jdbc.update("UPDATE orders SET status = ?, remaining_quantity = 0 WHERE id = ?", status, orderId);
    }

    private String orderMessage(String status) {
        return switch (status) {
            case "FILLED" -> "주문이 체결되었습니다.";
            case "PARTIAL" -> "일부 체결 후 잔량은 취소되었습니다.";
            case "CANCELLED" -> "체결할 호가가 없어 주문이 취소되었습니다.";
            default -> "지정가 주문이 호가창에 접수되었습니다.";
        };
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

    private void seedDailySummaries() {
        jdbc.update("""
                INSERT INTO daily_market_summaries (stock_id, trading_date, open_price, close_price, total_volume)
                SELECT id, CURRENT_DATE, current_price, current_price, 0
                FROM stocks s
                WHERE NOT EXISTS (
                    SELECT 1 FROM daily_market_summaries d
                    WHERE d.stock_id = s.id AND d.trading_date = CURRENT_DATE
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

    /** Seed only new rows so a backend restart never reapplies old headlines. */
    private void initializeNewsPriceState() {
        ensureNewsPriceState();
        List<String> codes = jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
        for (String code : codes) {
            long id = stockId(code);
            double currentBias = recentNewsBias(code).rate();
            jdbc.update("INSERT IGNORE INTO news_price_state (stock_id, applied_bias) VALUES (?, ?)", id, currentBias);
        }
    }

    private void ensureMarketLock() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS market_locks (
                  id TINYINT PRIMARY KEY
                )
                """);
        jdbc.update("INSERT IGNORE INTO market_locks (id) VALUES (1)");
    }

    /** Reject accidental double-clicks and abusive bursts from human users. */
    private void enforceOrderLimits(long userId, long stockId, String side, String orderType,
                                    int quantity, long price) {
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        if ("BOT".equals(passwordHash)) return;
        Integer recentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? "
                        + "AND created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL " + USER_ORDER_WINDOW_SECONDS + " SECOND)",
                Integer.class, userId);
        if (recentCount != null && recentCount >= USER_ORDER_LIMIT)
            throw new IllegalArgumentException("주문 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        Integer duplicateCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? AND stock_id = ? AND side = ? AND order_type = ? "
                        + "AND quantity = ? AND COALESCE(price, 0) = ? "
                        + "AND created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL " + DUPLICATE_ORDER_WINDOW_SECONDS + " SECOND)",
                Integer.class, userId, stockId, side, orderType, quantity, price);
        if (duplicateCount != null && duplicateCount > 0)
            throw new IllegalArgumentException("동일한 주문이 이미 접수되었습니다.");
    }

    private long nextSimulationTick() {
        Long current = jdbc.queryForObject("SELECT tick FROM simulation_state WHERE id = 1 FOR UPDATE", Long.class);
        long next = (current == null ? 0 : current) + 1;
        jdbc.update("UPDATE simulation_state SET tick = ? WHERE id = 1", next);
        return next;
    }

    /** Serialize order matching across multiple backend instances. */
    private void acquireMarketLock() {
        jdbc.queryForObject("SELECT id FROM market_locks WHERE id = 1 FOR UPDATE", Integer.class);
    }

    /** 기존 호가도 새 호가 단위 규칙을 따르도록 보정한다. 예약 현금 차액은 함께 정산한다. */
    private void normalizeOpenOrderPrices() {
        List<OpenLimitOrder> orders = jdbc.query("""
                SELECT o.id, o.user_id, s.stock_code, o.side, o.price, o.remaining_quantity,
                       COALESCE(o.reserved_cash, 0), (u.password_hash = 'BOT') AS is_bot
                FROM orders o
                JOIN stocks s ON s.id = o.stock_id
                JOIN users u ON u.id = o.user_id
                WHERE o.status = 'OPEN' AND o.order_type = 'LIMIT' AND o.price IS NOT NULL
                """, (rs, row) -> new OpenLimitOrder(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getLong(5), rs.getInt(6), rs.getLong(7), rs.getBoolean(8)));
        for (OpenLimitOrder order : orders) {
            PriceBand allowedBand = order.bot() ? botPriceBand(order.stockCode()) : dailyPriceBand(order.stockCode());
            long rounded = "BUY".equals(order.side()) ? floorToTick(order.price()) : ceilToTick(order.price());
            long normalized = order.bot()
                    ? botQuotePrice(allowedBand, order.side(), rounded)
                    : allowedBand.clamp(rounded);
            if ("BUY".equals(order.side())) {
                long required;
                try {
                    long normalizedGross = Math.multiplyExact(normalized, order.remainingQuantity());
                    required = normalizedGross + feeFor(normalizedGross);
                } catch (ArithmeticException error) {
                    if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
                    jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", order.id());
                    continue;
                }
                long difference = order.reservedCash() - required;
                if (difference < 0 && jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ? AND cash >= ?", -difference, order.userId(), -difference) == 0) {
                    if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
                    jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", order.id());
                    continue;
                }
                if (difference > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", difference, order.userId());
                if (normalized != order.price() || order.reservedCash() != required)
                    jdbc.update("UPDATE orders SET price = ?, reserved_cash = ? WHERE id = ?", normalized, required, order.id());
            } else {
                if (normalized != order.price()) jdbc.update("UPDATE orders SET price = ? WHERE id = ?", normalized, order.id());
            }
        }
    }

    /** Finish any legacy pending deliveries left by an older T+1 database. */
    private void settleDuePayments() {
        List<PendingSettlement> due = jdbc.query("""
                SELECT id, buyer_id, seller_id, stock_id, quantity, gross_amount, seller_fee
                FROM settlements
                WHERE status = 'PENDING'
                ORDER BY id ASC
                FOR UPDATE
                """, (rs, row) -> new PendingSettlement(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getInt(5), rs.getLong(6), rs.getLong(7)));
        for (PendingSettlement settlement : due) {
            long sellerNet = settlement.grossAmount() - settlement.sellerFee();
            jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", sellerNet, settlement.sellerId());
            jdbc.update("""
                    UPDATE portfolios
                    SET settled_quantity = LEAST(quantity, COALESCE(settled_quantity, quantity) + ?)
                    WHERE user_id = ? AND stock_id = ?
                    """, settlement.quantity(), settlement.buyerId(), settlement.stockId());
            jdbc.update("UPDATE settlements SET status = 'SETTLED', settled_at = CURRENT_TIMESTAMP WHERE id = ?", settlement.id());
        }
    }

    private void expireOrders() {
        List<OrderReservation> expired = jdbc.query("""
                SELECT id, user_id, COALESCE(reserved_cash, 0)
                FROM orders
                WHERE status = 'OPEN' AND expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP
                FOR UPDATE
                """, (rs, row) -> new OrderReservation(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
        for (OrderReservation order : expired) {
            if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
            jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", order.id());
        }
    }

    private void cancelOrderInternal(long orderId) {
        OrderState order = findOrder(orderId);
        if (!"OPEN".equals(order.status())) return;
        if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
        jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", orderId);
    }

    private long availableCash(long userId) {
        return jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
    }

    private int availableQuantity(long userId, long stockId) {
        int owned = jdbc.query("SELECT COALESCE(settled_quantity, quantity) FROM portfolios WHERE user_id = ? AND stock_id = ?", (rs, row) -> rs.getInt(1), userId, stockId).stream().findFirst().orElse(0);
        Integer reserved = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_quantity), 0) FROM orders WHERE user_id = ? AND stock_id = ? AND side = 'SELL' AND status = 'OPEN'", Integer.class, userId, stockId);
        return owned - (reserved == null ? 0 : reserved);
    }

    private OrderState findOrder(long orderId) {
        return jdbc.query("""
                SELECT id, user_id, COALESCE(price, 0), quantity, remaining_quantity, status, order_type,
                       COALESCE(reserved_cash, 0), COALESCE(reserved_quantity, 0)
                FROM orders WHERE id = ?
                """, (rs, row) -> new OrderState(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                rs.getInt(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getInt(9)), orderId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));
    }

    private boolean earlier(MatchRow first, MatchRow second) {
        int compared = first.createdAt().compareTo(second.createdAt());
        return compared < 0 || (compared == 0 && first.id() < second.id());
    }

    private void adjustQuantity(long stockId, long userId, int amount, long price, long fee, boolean buy) {
        boolean currentExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portfolios WHERE user_id = ? AND stock_id = ?", Integer.class,
                userId, stockId) > 0;
        HoldingState state = holdingState(stockId, userId);
        int next = buy ? state.quantity() + amount : state.quantity() - amount;
        if (next < 0) throw new IllegalArgumentException("보유 수량이 부족합니다.");
        long gross = price * (long) amount;
        long nextAverage = buy && next > 0
                ? Math.round(((double) state.averagePrice() * state.quantity() + gross + fee) / next)
                : (next == 0 ? 0 : state.averagePrice());
        int nextSettled = buy ? Math.min(next, state.settledQuantity() + amount)
                : Math.max(0, state.settledQuantity() - amount);
        long nextRealized = buy ? state.realizedProfitLoss()
                : state.realizedProfitLoss() + gross - fee - state.averagePrice() * (long) amount;
        if (!currentExists) {
            jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, userId, stockId, next, nextSettled, nextAverage, nextRealized);
        } else {
            jdbc.update("""
                    UPDATE portfolios SET quantity = ?, settled_quantity = ?, average_price = ?, realized_profit_loss = ?
                    WHERE user_id = ? AND stock_id = ?
                    """, next, nextSettled, nextAverage, nextRealized, userId, stockId);
        }
    }

    private HoldingState holdingState(long stockId, long userId) {
        return jdbc.query("""
                SELECT quantity, COALESCE(settled_quantity, quantity), average_price,
                       COALESCE(realized_profit_loss, 0)
                FROM portfolios WHERE user_id = ? AND stock_id = ?
                """, (rs, row) -> new HoldingState(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)), userId, stockId)
                .stream().findFirst().orElse(new HoldingState(0, 0, 0, 0));
    }

    private void restoreHolding(long stockId, long userId, int quantity, int settledQuantity,
                                long averagePrice, long realizedProfitLoss) {
        int updated = jdbc.update("""
                UPDATE portfolios SET quantity = ?, settled_quantity = ?, average_price = ?, realized_profit_loss = ?
                WHERE user_id = ? AND stock_id = ?
                """, quantity, settledQuantity, averagePrice, realizedProfitLoss, userId, stockId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, userId, stockId, quantity, settledQuantity, averagePrice, realizedProfitLoss);
        }
    }

    private void rebuildHoldingFromLedger(long stockId, long userId) {
        List<HoldingSnapshot> snapshots = jdbc.query("""
                SELECT settlements.trade_id, settlements.buyer_id, buyer_quantity_before, buyer_settled_quantity_before,
                       buyer_average_price_before, buyer_realized_profit_loss_before,
                       seller_quantity_before, seller_settled_quantity_before,
                       seller_average_price_before, seller_realized_profit_loss_before
                FROM settlements
                JOIN trades t0 ON t0.id = settlements.trade_id
                WHERE settlements.stock_id = ? AND (settlements.buyer_id = ? OR settlements.seller_id = ?)
                  AND t0.created_at > COALESCE((SELECT account_reset_at FROM users WHERE id = ?), '1970-01-01')
                  AND buyer_quantity_before IS NOT NULL
                  AND buyer_settled_quantity_before IS NOT NULL
                  AND buyer_average_price_before IS NOT NULL
                  AND buyer_realized_profit_loss_before IS NOT NULL
                  AND seller_quantity_before IS NOT NULL
                  AND seller_settled_quantity_before IS NOT NULL
                  AND seller_average_price_before IS NOT NULL
                  AND seller_realized_profit_loss_before IS NOT NULL
                ORDER BY trade_id ASC
                LIMIT 1
                """, (rs, row) -> {
            long tradeId = rs.getLong("trade_id");
            boolean buyer = rs.getLong("buyer_id") == userId;
            HoldingState state = buyer
                    ? new HoldingState(rs.getInt("buyer_quantity_before"), rs.getInt("buyer_settled_quantity_before"),
                    rs.getLong("buyer_average_price_before"), rs.getLong("buyer_realized_profit_loss_before"))
                    : new HoldingState(rs.getInt("seller_quantity_before"), rs.getInt("seller_settled_quantity_before"),
                    rs.getLong("seller_average_price_before"), rs.getLong("seller_realized_profit_loss_before"));
            return new HoldingSnapshot(tradeId, state);
        }, stockId, userId, userId, userId);
        if (snapshots.isEmpty()) throw new IllegalArgumentException("거래 원장을 복구할 기준을 찾을 수 없습니다.");
        HoldingState state = snapshots.get(0).state();
        List<LedgerTrade> trades = jdbc.query("""
                SELECT t.id, t.buyer_id, t.seller_id, t.quantity, t.price,
                       t.buyer_fee, t.seller_fee, st.status
                FROM trades t
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE t.stock_id = ? AND (t.buyer_id = ? OR t.seller_id = ?)
                  AND t.created_at > COALESCE((SELECT account_reset_at FROM users WHERE id = ?), '1970-01-01')
                  AND t.id >= ? AND (st.id IS NULL OR st.status <> 'CANCELLED')
                ORDER BY t.id ASC
                """, (rs, row) -> new LedgerTrade(rs.getLong("id"), rs.getLong("buyer_id"),
                rs.getLong("seller_id"), rs.getInt("quantity"), rs.getLong("price"),
                rs.getLong("buyer_fee"), rs.getLong("seller_fee"), rs.getString("status")),
                stockId, userId, userId, userId, snapshots.get(0).tradeId());
        for (LedgerTrade trade : trades) {
            boolean buy = trade.buyerId() == userId;
            long fee = buy ? trade.buyerFee() : trade.sellerFee();
            long gross = trade.price() * (long) trade.quantity();
            int nextQuantity = buy ? state.quantity() + trade.quantity() : state.quantity() - trade.quantity();
            if (nextQuantity < 0) throw new IllegalArgumentException("거래 원장을 복구할 수 없습니다.");
            long nextAverage = buy && nextQuantity > 0
                    ? Math.round(((double) state.averagePrice() * state.quantity() + gross + fee) / nextQuantity)
                    : (nextQuantity == 0 ? 0 : state.averagePrice());
            int nextSettled = buy ? state.settledQuantity()
                    : Math.max(0, state.settledQuantity() - trade.quantity());
            long nextRealized = buy ? state.realizedProfitLoss()
                    : state.realizedProfitLoss() + gross - fee - state.averagePrice() * (long) trade.quantity();
            if (buy && (trade.settlementStatus() == null || "SETTLED".equals(trade.settlementStatus())))
                nextSettled = Math.min(nextQuantity, nextSettled + trade.quantity());
            state = new HoldingState(nextQuantity, nextSettled, nextAverage, nextRealized);
        }
        restoreHolding(stockId, userId, state.quantity(), state.settledQuantity(),
                state.averagePrice(), state.realizedProfitLoss());
    }

    private ExecutionSummary executionSummary(long orderId, String side) {
        List<ExecutionSummary> rows = jdbc.query("""
                SELECT COALESCE(SUM(CASE WHEN ? = 'BUY' THEN t.buyer_fee ELSE t.seller_fee END), 0) AS fee,
                       CASE WHEN COUNT(t.id) = 0 THEN 'NONE'
                            WHEN SUM(CASE WHEN st.status = 'CANCELLED' THEN 1 ELSE 0 END) > 0 THEN 'CANCELLED'
                            WHEN SUM(CASE WHEN st.status = 'PENDING' THEN 1 ELSE 0 END) > 0 THEN 'PENDING'
                            ELSE 'SETTLED' END AS settlement_status,
                       MIN(CASE WHEN st.status = 'PENDING' THEN st.settlement_at ELSE NULL END) AS settlement_at
                FROM trades t
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE t.buy_order_id = ? OR t.sell_order_id = ?
                """, (rs, row) -> new ExecutionSummary(rs.getLong("fee"), rs.getString("settlement_status"),
                rs.getTimestamp("settlement_at") == null ? null : databaseInstant(rs.getTimestamp("settlement_at")).toString()),
                side, orderId, orderId);
        return rows.isEmpty() ? new ExecutionSummary(0, "NONE", null) : rows.get(0);
    }

    private long feeFor(long grossAmount) {
        if (grossAmount <= 0) return 0;
        return Math.max(1L, Math.round(grossAmount * TRADING_FEE_RATE));
    }

    private record MatchRow(long id, long userId, long price, int quantity, int remainingQuantity, String orderType,
                            Instant createdAt, long reservedCash, int reservedQuantity, String side, boolean bot) { }
    private record MatchSummary(long lastTradePrice, long totalQuantity, long totalNotional, String aggressorSide,
                                long buyQuantity, long sellQuantity) {
        private static MatchSummary empty() { return new MatchSummary(0, 0, 0, null, 0, 0); }
        private boolean hasTrades() { return totalQuantity > 0; }
        private MatchSummary add(int quantity, long price, String side) {
            return new MatchSummary(price, totalQuantity + quantity, totalNotional + price * quantity, side,
                    buyQuantity + ("BUY".equals(side) ? quantity : 0), sellQuantity + ("SELL".equals(side) ? quantity : 0));
        }
        private MatchSummary merge(MatchSummary other) {
            if (!hasTrades()) return other;
            if (!other.hasTrades()) return this;
            return new MatchSummary(other.lastTradePrice, totalQuantity + other.totalQuantity,
                    totalNotional + other.totalNotional, other.aggressorSide,
                    buyQuantity + other.buyQuantity, sellQuantity + other.sellQuantity);
        }
        private long vwap() {
            return totalQuantity == 0 ? 0 : Math.round(totalNotional / (double) totalQuantity);
        }
    }
    private record OrderState(long id, long userId, long price, int quantity, int remainingQuantity, String status,
                              String orderType, long reservedCash, int reservedQuantity) { }
    private record OrderReservation(long id, long userId, long reservedCash) { }
    private record BotOpenOrder(long id, long price) { }
    private record TraderBotProfile(String username, String nickname, TraderStyle style) { }
    private enum TraderStyle { MOMENTUM, CONTRARIAN, VALUE, INTRADAY }
    private record OpenLimitOrder(long id, long userId, String stockCode, String side, long price,
                                  int remainingQuantity, long reservedCash, boolean bot) { }
    private record LiquidityDepth(int orders, long quantity) { }
    private record HoldingState(int quantity, int settledQuantity, long averagePrice, long realizedProfitLoss) { }
    private record PendingSettlement(long id, long buyerId, long sellerId, long stockId, int quantity,
                                     long grossAmount, long sellerFee) { }
    private record CancelableSettlement(long id, long tradeId, long buyerId, long sellerId, long stockId,
                                        int quantity, long grossAmount, long buyerFee, long sellerFee,
                                        Instant createdAt, Integer buyerQuantityBefore,
                                        Integer buyerSettledQuantityBefore, Long buyerAveragePriceBefore,
                                        Long buyerRealizedProfitLossBefore, Integer sellerQuantityBefore,
                                        Integer sellerSettledQuantityBefore, Long sellerAveragePriceBefore,
                                        Long sellerRealizedProfitLossBefore) {
        private boolean hasSnapshot() {
            return buyerQuantityBefore != null && buyerSettledQuantityBefore != null
                    && buyerAveragePriceBefore != null && buyerRealizedProfitLossBefore != null
                    && sellerQuantityBefore != null && sellerSettledQuantityBefore != null
                    && sellerAveragePriceBefore != null && sellerRealizedProfitLossBefore != null;
        }
    }
    private record HoldingSnapshot(long tradeId, HoldingState state) { }
    private record LedgerTrade(long id, long buyerId, long sellerId, int quantity, long price,
                               long buyerFee, long sellerFee, String settlementStatus) { }
    private record ExecutionSummary(long fee, String status, String settlementAt) { }
    private record VolumeBreakdown(long userBuy, long userSell, long botBuy, long botSell) { }
    private record RecentNews(String title, String description, double impact, double recency) { }
    private record NewsInfluence(double rate, boolean special) { }
    private record NewsBias(double rate, boolean special) { }

    private Stock findStock(String code) {
        return stocks().stream().filter(stock -> stock.code().equals(code)).findFirst().orElse(null);
    }

    /** The JDBC timestamp already carries the normalized instant from the UTC DB session. */
    private Instant databaseInstant(Timestamp timestamp) {
        return timestamp.toInstant();
    }

    private long stockId(String code) {
        return jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code = ?", Long.class, code);
    }

    private void move(String code, double movement, int volume) {
        Stock old = findStock(code);
        long requested = Math.max(100, Math.round(old.price() * (1 + movement / 100)));
        moveToPrice(code, requested, volume);
    }

    private long dailyReferencePrice(String code) {
        seedDailySummaries();
        List<Long> references = jdbc.query("""
                SELECT d.open_price
                FROM daily_market_summaries d
                JOIN stocks s ON s.id = d.stock_id
                WHERE s.stock_code = ? AND d.trading_date = CURRENT_DATE
                """, (rs, row) -> rs.getLong(1), code);
        return references.isEmpty() ? findStock(code).price() : references.get(0);
    }

    private PriceBand dailyPriceBand(String code) {
        return dailyBand(dailyReferencePrice(code));
    }

    private PriceBand botPriceBand(String code) {
        return botBand(dailyReferencePrice(code));
    }

    private void moveToPrice(String code, long price, long volume) {
        PriceBand dailyBand = dailyPriceBand(code);
        long next = dailyBand.clamp(Math.max(100, price));
        jdbc.update("UPDATE stocks SET previous_price = current_price, current_price = ?, total_volume = total_volume + ? WHERE stock_code = ?", next, volume, code);
        jdbc.update("INSERT INTO stock_price_history (stock_id, price) SELECT id, ? FROM stocks WHERE stock_code = ?", next, code);
        recordDailyTrade(code, next, volume);
        protection.observeMarket();
    }

    private void recordDailyTrade(String code, long price, long volume) {
        jdbc.update("""
                INSERT INTO daily_market_summaries (stock_id, trading_date, open_price, close_price, total_volume)
                SELECT id, CURRENT_DATE, ?, ?, ? FROM stocks WHERE stock_code = ?
                ON DUPLICATE KEY UPDATE close_price = VALUES(close_price), total_volume = daily_market_summaries.total_volume + VALUES(total_volume)
                """, price, price, Math.max(0, volume), code);
    }

    private void moveBotPrice(String code, long requestedPrice, long volume, boolean specialNews) {
        Stock current = findStock(code);
        double impactShare = BOT_TRADE_IMPACT_MIN
                + tickRandom.nextDouble() * (BOT_TRADE_IMPACT_MAX - BOT_TRADE_IMPACT_MIN);
        long blendedPrice = Math.round(current.price()
                + (requestedPrice - current.price()) * impactShare);
        double stepRate = specialNews ? BOT_SPECIAL_PRICE_STEP_RATE : BOT_PRICE_STEP_RATE;
        long maxStep = Math.max(tickSize(current.price()), Math.round(current.price() * stepRate));
        long stepLower = Math.max(100, current.price() - maxStep);
        long stepUpper = current.price() + maxStep;
        long bounded = Math.max(stepLower, Math.min(stepUpper, blendedPrice));
        bounded = bounded >= current.price() ? ceilToTick(bounded) : floorToTick(bounded);
        PriceBand botBand = botPriceBand(code);
        long headroom = Math.max(tickSize(botBand.referencePrice()) * 2,
                Math.round(botBand.referencePrice() * BOT_LIMIT_HEADROOM_RATE));
        long internalLower = Math.min(botBand.upperPrice(), botBand.lowerPrice() + headroom);
        long internalUpper = Math.max(botBand.lowerPrice(), botBand.upperPrice() - headroom);
        bounded = Math.max(internalLower, Math.min(internalUpper, bounded));
        moveToPrice(code, bounded, volume);
    }

    /**
     * Applies the change in the aggregate 24-hour news bias directly to the
     * reference price. The state table makes this idempotent: a restart or a
     * repeated scheduler tick cannot apply the same headline twice.
     * News moves price only (volume remains zero); order matching separately
     * records actual traded volume and VWAP.
     */
    private void applyNewsPriceDrift(String code, NewsBias newsBias) {
        if (!protection.continuous(code)) return;
        long id = stockId(code);
        Double previous = jdbc.queryForObject(
                "SELECT applied_bias FROM news_price_state WHERE stock_id = ?",
                Double.class, id);
        if (previous == null) {
            jdbc.update("INSERT IGNORE INTO news_price_state (stock_id, applied_bias) VALUES (?, ?)",
                    id, newsBias.rate());
            return;
        }
        double next = newsBias.rate();
        double delta = next - previous;
        // Keep sub-tick changes pending so news recency decay is not lost.
        // The applied value advances only when the accumulated delta is large
        // enough to move at least one meaningful price increment.
        if (Math.abs(delta) < 0.00005) return;
        Stock current = findStock(code);
        if (current == null) return;
        long requested = Math.max(100, Math.round(current.price() * (1.0 + delta * NEWS_DIRECT_RATE_SHARE)));
        long nextPrice = delta >= 0 ? ceilToTick(requested) : floorToTick(requested);
        jdbc.update("UPDATE news_price_state SET applied_bias = ?, updated_at = CURRENT_TIMESTAMP WHERE stock_id = ?",
                next, id);
        if (nextPrice != current.price()) {
            moveToPrice(code, nextPrice, 0);
            protection.observeSyntheticMove(code, current.price(), findStock(code).price());
        }
    }

    private NewsBias recentNewsBias(String code) {
        List<RecentNews> recentNews = jdbc.query("""
                SELECT e.title, e.description, e.impact,
                       GREATEST(0, LEAST(1, 1 - TIMESTAMPDIFF(SECOND,
                           COALESCE(e.published_at, e.created_at), CURRENT_TIMESTAMP) / 86400.0)) AS recency
                FROM market_events e JOIN stocks s ON s.id = e.stock_id
                WHERE e.event_type = 'NEWS' AND s.stock_code = ?
                  AND COALESCE(e.published_at, e.created_at) >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                ORDER BY COALESCE(e.published_at, e.created_at) DESC, e.id DESC
                LIMIT 100
                """, (rs, row) -> new RecentNews(
                rs.getString("title"), rs.getString("description"), rs.getDouble("impact"),
                Math.max(0.0, Math.min(1.0, rs.getDouble("recency")))), code).stream()
                // Price formation uses the ten newest relevant headlines for
                // this stock, independently of the ten-item global feed.
                .filter(news -> NewsRelevance.isRelevant(code, news.title(), news.description()))
                .limit(10)
                .toList();
        double total = 0.0;
        boolean special = false;
        for (RecentNews news : recentNews) {
            NewsInfluence influence = newsInfluence(news);
            total += influence.rate();
            special |= influence.special();
        }
        double aggregateLimit = special ? NEWS_24H_SPECIAL_RATE : NEWS_24H_BASE_RATE;
        return new NewsBias(Math.max(-aggregateLimit, Math.min(aggregateLimit, total)), special);
    }

    private NewsInfluence newsInfluence(RecentNews news) {
        double absoluteImpact = Math.min(10.0, Math.abs(news.impact()));
        if (absoluteImpact == 0.0) return new NewsInfluence(0.0, false);
        boolean majorIncident = news.impact() <= NEWS_MAJOR_INCIDENT_IMPACT_THRESHOLD
                && NewsRelevance.hasIncidentContext(news.title(), news.description());
        boolean special = majorIncident || absoluteImpact >= NEWS_SPECIAL_IMPACT_THRESHOLD;
        double singleLimit = majorIncident ? NEWS_MAJOR_INCIDENT_RATE
                : special ? NEWS_SINGLE_SPECIAL_RATE : NEWS_SINGLE_BASE_RATE;
        double rate = singleLimit * (absoluteImpact / 10.0) * news.recency();
        return new NewsInfluence(Math.copySign(rate, news.impact()), special);
    }

    private Portfolio portfolioUnsafe(long userId) {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        List<Position> positions = jdbc.query("""
                SELECT s.stock_code, p.quantity, COALESCE(p.settled_quantity, p.quantity) AS settled_quantity,
                       p.average_price, COALESCE(p.realized_profit_loss, 0) AS realized_profit_loss,
                       p.quantity * s.current_price AS market_value,
                       p.quantity * (s.current_price - p.average_price) AS profit_loss
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                WHERE p.user_id = ? AND p.quantity > 0
                ORDER BY p.id
                """, (rs, row) -> new Position(rs.getString("stock_code"),
                rs.getInt("quantity"), rs.getInt("settled_quantity"),
                Math.max(0, rs.getInt("quantity") - rs.getInt("settled_quantity")),
                rs.getLong("average_price"), rs.getLong("market_value"), rs.getLong("profit_loss"),
                rs.getLong("average_price") == 0 ? 0 :
                        Math.round(rs.getLong("profit_loss") * 10000.0 /
                                (rs.getInt("quantity") * rs.getLong("average_price"))) / 100.0,
                rs.getLong("realized_profit_loss")), userId);
        long reservedCash = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_cash), 0) FROM orders WHERE user_id = ? AND status = 'OPEN'", Long.class, userId);
        long unsettledCash = jdbc.queryForObject("""
                SELECT COALESCE(SUM(gross_amount - seller_fee), 0)
                FROM settlements WHERE seller_id = ? AND status = 'PENDING'
                """, Long.class, userId);
        long unsettledAssetValue = positions.stream()
                .mapToLong(position -> (long) position.unsettledQuantity() * currentPrice(position.stockCode()))
                .sum();
        long totalFees = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN buyer_id = ? THEN buyer_fee ELSE 0 END
                                  + CASE WHEN seller_id = ? THEN seller_fee ELSE 0 END), 0)
                FROM trades
                WHERE (buyer_id = ? OR seller_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM settlements st WHERE st.trade_id = trades.id AND st.status = 'CANCELLED')
                  AND created_at > COALESCE((SELECT account_reset_at FROM users WHERE id = ?), '1970-01-01')
                """, Long.class, userId, userId, userId, userId, userId);
        long realizedProfitLoss = jdbc.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(realized_profit_loss, 0)), 0)
                FROM portfolios WHERE user_id = ?
                """, Long.class, userId);
        long attendanceRewardCash = jdbc.queryForObject("""
                SELECT COALESCE(SUM(reward_cash), 0)
                FROM attendance_rewards
                WHERE user_id = ?
                """, Long.class, userId);
        long assetValue = positions.stream().mapToLong(Position::marketValue).sum();
        return new Portfolio(cash, assetValue, cash + reservedCash + unsettledCash + assetValue,
                positions, unsettledCash, unsettledAssetValue, totalFees, realizedProfitLoss, attendanceRewardCash);
    }

    private long currentPrice(String code) {
        return findStock(code).price();
    }

    private double changePercent(long current, long previous) {
        if (previous == 0) return 0;
        return Math.round((current - previous) * 10_000.0 / previous) / 100.0;
    }
}
