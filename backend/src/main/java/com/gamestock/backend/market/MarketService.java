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
import java.util.Map;
import java.time.Instant;
import java.util.Random;

import static com.gamestock.backend.market.MarketModels.*;

@Service
public class MarketService {
    private static final Logger log = LoggerFactory.getLogger(MarketService.class);
    private static final long STARTING_CASH = 1_000_000L;
    /** GameStock charges a small, transparent 0.10% commission per side. */
    private static final double TRADING_FEE_RATE = 0.001;
    private static final int MAX_BOT_OPEN_ORDERS_PER_SIDE = 40;
    private static final int USER_ORDER_WINDOW_SECONDS = 10;
    private static final int USER_ORDER_LIMIT = 20;
    private static final int DUPLICATE_ORDER_WINDOW_SECONDS = 2;
    private static final String DEMO_USERNAME = "demo";

    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Map<String, Double> botMomentum = new HashMap<>();
    @Value("${gamestock.simulation.seed:20260910}")
    private long simulationSeed;
    private Random tickRandom = new Random(20260910L);
    private long demoUserId;

    public MarketService(ApplicationEventPublisher events, JdbcTemplate jdbc) {
        this.events = events;
        this.jdbc = jdbc;
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

        renameExistingStock("NEXA", "UMA", "네사: 크로니클", "우마무스메 프리티더비");
        renameExistingStock("STAR", "BA", "스타라이트 아레나", "블루 아카이브");
        renameExistingStock("MOMO", "GOV", "모모 팜", "승리의 여신: 니케");
        removeExistingStock("VOID", "보이드 러너");

        insertStock("UMA", "우마무스메 프리티더비", 12_450L);
        insertStock("BA", "블루 아카이브", 8_230L);
        insertStock("GOV", "승리의 여신: 니케", 21_430L);
        seedPriceHistory();
        seedDailySummaries();
        normalizeOpenOrderPrices();

        removeDefaultEvents();

        demoUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, DEMO_USERNAME);
        // Convert any rows created by the former T+1 implementation to the
        // new immediate-settlement state during startup.
        settleDuePayments();
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
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'market_events' AND column_name = 'published_at'", Integer.class);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE market_events ADD COLUMN published_at TIMESTAMP NULL AFTER impact");
        jdbc.update("UPDATE market_events SET published_at = created_at WHERE published_at IS NULL");
    }

    private void insertStock(String code, String name, long price) {
        jdbc.update("""
                INSERT IGNORE INTO stocks (game_id, stock_code, current_price, previous_price, total_volume)
                SELECT id, ?, ?, ?, ? FROM games WHERE name = ?
                """, code, price, price, 120_000L, name);
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
                rs.getLong("total_volume")));
    }

    public synchronized List<MarketEvent> marketEvents() {
        return jdbc.query("""
                SELECT s.stock_code, e.title, e.impact
                FROM market_events e LEFT JOIN stocks s ON s.id = e.stock_id
                WHERE e.event_type = 'NEWS'
            ORDER BY COALESCE(e.published_at, e.created_at) DESC, e.id DESC
            LIMIT 10
                """, (rs, row) -> {
            double impact = rs.getDouble("impact");
            return new MarketEvent(rs.getString("stock_code"), rs.getString("title"),
                    (int) Math.round(impact), impact >= 0 ? "positive" : "negative");
        });
    }

    /** GameStock은 장 마감 없이 24시간 주문을 접수하는 게임형 시장이다. */
    public synchronized MarketStatus marketStatus() {
        return new MarketStatus(true, "24H", "Asia/Seoul", "24시간 거래 가능");
    }

    public synchronized List<MarketEvent> stockNews(String code) {
        return jdbc.query("""
                SELECT s.stock_code, e.title, e.impact
                FROM market_events e JOIN stocks s ON s.id = e.stock_id
                WHERE e.event_type = 'NEWS' AND s.stock_code = ?
                ORDER BY COALESCE(e.published_at, e.created_at) DESC, e.id DESC
                LIMIT 5
                """, (rs, row) -> {
            double impact = rs.getDouble("impact");
            return new MarketEvent(rs.getString("stock_code"), rs.getString("title"),
                    (int) Math.round(impact), impact >= 0 ? "positive" : "negative");
        }, code.toUpperCase(Locale.ROOT));
    }

    public synchronized List<RankingEntry> ranking() {
        settleDuePayments();
        List<RankingEntry> entries = jdbc.query("""
                SELECT u.nickname, NULL AS profile_image_url, u.cash,
                       COALESCE((SELECT SUM(o.reserved_cash) FROM orders o WHERE o.user_id = u.id AND o.status = 'OPEN'), 0) AS reserved_cash,
                       COALESCE((SELECT SUM(st.gross_amount - st.seller_fee) FROM settlements st WHERE st.seller_id = u.id AND st.status = 'PENDING'), 0) AS unsettled_cash,
                       COALESCE(SUM(CASE WHEN p.quantity > 0 THEN p.quantity * s.current_price ELSE 0 END), 0) AS asset_value
                FROM users u
                LEFT JOIN portfolios p ON p.user_id = u.id
                LEFT JOIN stocks s ON s.id = p.stock_id
                WHERE u.password_hash <> 'BOT' AND u.username <> 'demo'
                GROUP BY u.id, u.nickname, u.cash
                ORDER BY (u.cash + reserved_cash + asset_value) DESC, u.id ASC
                LIMIT 100
                """, (rs, row) -> {
            long assetValue = rs.getLong("asset_value");
            long cash = rs.getLong("cash");
            long reservedCash = rs.getLong("reserved_cash");
            long unsettledCash = rs.getLong("unsettled_cash");
            long totalAsset = cash + reservedCash + unsettledCash + assetValue;
            double changePercent = (totalAsset - STARTING_CASH) * 100.0 / STARTING_CASH;
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
                rs.getInt("reserved_quantity"), rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant().toString()), userId);
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
                rs.getTimestamp("settlement_at").toInstant().toString(),
                rs.getTimestamp("created_at").toInstant().toString(),
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
                rs.getTimestamp("created_at").toInstant(),
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
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getLong("fee"), rs.getString("settlement_status"),
                rs.getTimestamp("settlement_at") == null ? null : rs.getTimestamp("settlement_at").toInstant().toString()),
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
                rs.getLong("price"), rs.getString("order_type"), rs.getTimestamp("created_at").toInstant().toString()),
                code.toUpperCase(Locale.ROOT));
    }

    public synchronized List<PricePoint> priceHistory(String code) {
        List<PricePoint> points = jdbc.query("""
                SELECT h.price, h.recorded_at
                FROM stock_price_history h JOIN stocks s ON s.id = h.stock_id
                WHERE s.stock_code = ?
                ORDER BY h.recorded_at DESC, h.id DESC
                LIMIT 200
                """, (rs, row) -> new PricePoint(
                rs.getLong("price"),
                rs.getTimestamp("recorded_at").toInstant().toString()),
                code.toUpperCase(Locale.ROOT));
            Collections.reverse(points);
            return points;
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
        String code = request.stockCode().toUpperCase(Locale.ROOT);
        String side = request.side().toUpperCase(Locale.ROOT);
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new IllegalArgumentException("주문 구분은 BUY 또는 SELL이어야 합니다.");
        }

        Stock stock = findStock(code);
        if (stock == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        String orderType = request.orderType() == null || request.orderType().isBlank() ? "MARKET" : request.orderType().toUpperCase(Locale.ROOT);
        if (!"MARKET".equals(orderType) && !"LIMIT".equals(orderType)) throw new IllegalArgumentException("주문 유형은 MARKET 또는 LIMIT이어야 합니다.");
        long orderPrice = "LIMIT".equals(orderType) ? (request.price() == null ? 0 : request.price()) : stock.price();
        if ("LIMIT".equals(orderType) && orderPrice <= 0) throw new IllegalArgumentException("지정가를 입력해 주세요.");
        if ("LIMIT".equals(orderType) && orderPrice % tickSize(orderPrice) != 0)
            throw new IllegalArgumentException("호가 단위(" + tickSize(orderPrice) + "원)에 맞는 가격을 입력해 주세요.");
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
        return new OrderResult(orderMessage(afterMatch.status()), code, side, requestedQuantity, executedPrice,
                afterMatch.status(), portfolioUnsafe(userId), execution.fee(), execution.status(), execution.settlementAt());
    }

    @Scheduled(fixedRate = 5_000)
    @Transactional
    public synchronized void simulateBots() {
        acquireMarketLock();
        settleDuePayments();
        expireOrders();
        long tick = nextSimulationTick();
        // 동일한 시드와 DB 상태라면 틱별 난수열과 봇 주문 흐름을 재현할 수 있다.
        tickRandom = new Random(simulationSeed ^ (tick * 0x9E3779B97F4A7C15L));
        List<String> codes = jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
        if (codes.isEmpty()) return;
        String code = codes.get(tickRandom.nextInt(codes.size()));
        // 봇은 현재가 양옆에 유동성을 공급하고, 별도의 소량 시장가 주문으로 일부만 소비한다.
        createBotOrder(code, "BUY");
        createBotOrder(code, "SELL");
        // 한 번의 틱 안에서도 양쪽 봇을 모두 실행하되 순서를 섞어 고정된 매수→매도 패턴을 피한다.
        String firstSide = tickRandom.nextBoolean() ? "BUY" : "SELL";
        String secondSide = "BUY".equals(firstSide) ? "SELL" : "BUY";
        long firstOrderId = createBotMarketOrder(code, firstSide);
        MatchSummary firstMatched = matchOrders(code);
        if (firstOrderId > 0) cancelRemainingMarket(firstOrderId);
        long secondOrderId = createBotMarketOrder(code, secondSide);
        MatchSummary secondMatched = matchOrders(code);
        if (secondOrderId > 0) cancelRemainingMarket(secondOrderId);
        MatchSummary matched = firstMatched.merge(secondMatched);
        // 같은 틱의 양방향 체결은 VWAP 하나로 기록해 그래프에 인위적인 V자 패턴이 남지 않게 한다.
        if (matched.hasTrades()) moveBotPrice(code, naturalBotPrice(code, matched), matched.totalQuantity());
        trimBotLiquidity(code);
        events.publishEvent(new MarketChangedEvent(snapshot()));
    }

    private void createBotOrder(String code, String side) {
        long id = ensureBot(code, side);
        Stock stock = findStock(code);
        double offset = 0.002 + tickRandom.nextDouble() * 0.006;
        double rawPrice = stock.price() * ("BUY".equals(side) ? 1 - offset : 1 + offset);
        long price = "BUY".equals(side) ? floorToTick(rawPrice) : ceilToTick(rawPrice);
        int quantity = tickRandom.nextInt(26) + 5;
        long idStock = stockId(code);
        if ("BUY".equals(side)) {
            long amount = price * quantity;
            long fee = feeFor(amount);
            long required = amount + fee;
            int updated = jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ? AND cash >= ?", required, id, required);
            if (updated == 0) return;
            jdbc.update("INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, remaining_quantity, reserved_cash, reserved_quantity, status, expires_at) VALUES (?, ?, ?, 'LIMIT', ?, ?, ?, ?, 0, 'OPEN', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 24 HOUR))", id, idStock, side, price, quantity, quantity, required);
        } else {
            addBotInventory(code, quantity + 100, stock.price(), id);
            jdbc.update("INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, remaining_quantity, reserved_cash, reserved_quantity, status, expires_at) VALUES (?, ?, ?, 'LIMIT', ?, ?, ?, 0, ?, 'OPEN', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 24 HOUR))", id, idStock, side, price, quantity, quantity, quantity);
        }
    }

    private long createBotMarketOrder(String code, String side) {
        long id = ensureBot(code, side);
        Stock stock = findStock(code);
        int quantity = tickRandom.nextInt(22) + 3;
        long idStock = stockId(code);
        if ("SELL".equals(side)) addBotInventory(code, quantity + 200, stock.price(), id);
        jdbc.update("INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, remaining_quantity, reserved_cash, reserved_quantity, status, expires_at) VALUES (?, ?, ?, 'MARKET', NULL, ?, ?, 0, 0, 'OPEN', NULL)", id, idStock, side, quantity, quantity);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void trimBotLiquidity(String code) {
        long stockId = stockId(code);
        for (String side : List.of("BUY", "SELL")) {
            List<Long> openOrders = jdbc.query("""
                    SELECT o.id FROM orders o JOIN users u ON u.id = o.user_id
                    WHERE o.stock_id = ? AND o.side = ? AND o.order_type = 'LIMIT'
                      AND o.status = 'OPEN' AND u.password_hash = 'BOT'
                    ORDER BY o.created_at DESC, o.id DESC
                    """, (rs, row) -> rs.getLong(1), stockId, side);
            for (int index = MAX_BOT_OPEN_ORDERS_PER_SIDE; index < openOrders.size(); index++)
                cancelOrderInternal(openOrders.get(index));
        }
    }

    private long ensureBot(String code, String side) {
        String username = "bot_" + code + "_" + side.toLowerCase(Locale.ROOT);
        jdbc.update("INSERT IGNORE INTO users (username, password_hash, nickname, cash) VALUES (?, 'BOT', ?, 1000000000)", username, "봇 " + code + " " + side);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private MatchSummary matchOrders(String code) {
        long id = stockId(code);
        MatchSummary summary = MatchSummary.empty();
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

            if (!canSettle(buy, sell, quantity, tradePrice, id)) {
                if (buy.reservedCash() == 0 && availableCash(buy.userId()) < tradePrice * quantity) cancelOrderInternal(buy.id());
                if (sell.reservedQuantity() == 0 && availableQuantity(sell.userId(), id) < quantity) cancelOrderInternal(sell.id());
                continue;
            }
            settleTrade(id, buy, sell, maker, taker, quantity, tradePrice);
            summary = summary.add(quantity, tradePrice, taker.side());
        }
    }

    private MatchRow topOrder(long stockId, String side) {
        String priceOrder = "BUY".equals(side) ? "o.price DESC" : "o.price ASC";
        String sql = "SELECT o.id, o.user_id, COALESCE(o.price, 0), o.quantity, o.remaining_quantity, o.order_type, o.created_at, COALESCE(o.reserved_cash, 0), COALESCE(o.reserved_quantity, 0), o.side "
                + "FROM orders o WHERE o.stock_id = ? AND o.side = ? AND o.status = 'OPEN' AND o.remaining_quantity > 0 "
                + "ORDER BY CASE WHEN o.order_type = 'MARKET' THEN 1 ELSE 0 END DESC, " + priceOrder + ", o.created_at ASC, o.id ASC LIMIT 1";
        List<MatchRow> rows = jdbc.query(sql, (rs, row) -> new MatchRow(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getLong(8), rs.getInt(9), rs.getString(10)), stockId, side);
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
                SELECT id, user_id, side, price, remaining_quantity, COALESCE(reserved_cash, 0)
                FROM orders
                WHERE status = 'OPEN' AND order_type = 'LIMIT' AND price IS NOT NULL
                """, (rs, row) -> new OpenLimitOrder(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getLong(4), rs.getInt(5), rs.getLong(6)));
        for (OpenLimitOrder order : orders) {
            long normalized = "BUY".equals(order.side()) ? floorToTick(order.price()) : ceilToTick(order.price());
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

    /** 가격대별 호가 단위. 현재 게임 종목 가격대에 맞춘 교육용 단위다. */
    private static long tickSize(long price) {
        if (price <= 1_000) return 1;
        if (price <= 5_000) return 5;
        if (price <= 50_000) return 10;
        if (price <= 100_000) return 50;
        return 100;
    }

    private long floorToTick(double price) {
        long rounded = Math.max(1, Math.round(price));
        long tick = tickSize(rounded);
        return Math.max(tick, (rounded / tick) * tick);
    }

    private long ceilToTick(double price) {
        long rounded = Math.max(1, Math.round(price));
        long tick = tickSize(rounded);
        return Math.max(tick, ((rounded + tick - 1) / tick) * tick);
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
                rs.getTimestamp("settlement_at") == null ? null : rs.getTimestamp("settlement_at").toInstant().toString()),
                side, orderId, orderId);
        return rows.isEmpty() ? new ExecutionSummary(0, "NONE", null) : rows.get(0);
    }

    private long feeFor(long grossAmount) {
        if (grossAmount <= 0) return 0;
        return Math.max(1L, Math.round(grossAmount * TRADING_FEE_RATE));
    }

    private record MatchRow(long id, long userId, long price, int quantity, int remainingQuantity, String orderType,
                            Instant createdAt, long reservedCash, int reservedQuantity, String side) { }
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
        private long impactPrice() {
            long average = vwap();
            long imbalance = buyQuantity - sellQuantity;
            long impactTicks = Math.max(-3, Math.min(3,
                    Math.round(imbalance * 6.0 / Math.max(totalQuantity, 1))));
            return Math.max(1, average + impactTicks * tickSize(average));
        }
    }
    private record OrderState(long id, long userId, long price, int quantity, int remainingQuantity, String status,
                              String orderType, long reservedCash, int reservedQuantity) { }
    private record OrderReservation(long id, long userId, long reservedCash) { }
    private record OpenLimitOrder(long id, long userId, String side, long price, int remainingQuantity, long reservedCash) { }
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

    private Stock findStock(String code) {
        return stocks().stream().filter(stock -> stock.code().equals(code)).findFirst().orElse(null);
    }

    private long stockId(String code) {
        return jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code = ?", Long.class, code);
    }

    private void addBotInventory(String code, int amount, long price, long userId) {
        long id = stockId(code);
        List<HoldingState> current = jdbc.query("""
                SELECT quantity, COALESCE(settled_quantity, quantity), average_price,
                       COALESCE(realized_profit_loss, 0)
                FROM portfolios WHERE user_id = ? AND stock_id = ?
                """, (rs, row) -> new HoldingState(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)), userId, id);
        HoldingState state = current.isEmpty() ? new HoldingState(0, 0, 0, 0) : current.get(0);
        int nextQuantity = state.quantity() + amount;
        long nextAverage = nextQuantity == 0 ? 0 : Math.round(
                ((double) state.averagePrice() * state.quantity() + price * (long) amount) / nextQuantity);
        if (current.isEmpty()) {
            jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, userId, id, nextQuantity, nextQuantity, nextAverage, state.realizedProfitLoss());
        } else {
            jdbc.update("""
                    UPDATE portfolios SET quantity = ?, settled_quantity = ?, average_price = ?
                    WHERE user_id = ? AND stock_id = ?
                    """, nextQuantity, state.settledQuantity() + amount, nextAverage, userId, id);
        }
    }

    private void move(String code, double movement, int volume) {
        Stock old = findStock(code);
        long next = Math.max(100, Math.round(old.price() * (1 + movement / 100)));
        seedDailySummaries();
        jdbc.update("""
                UPDATE stocks
                SET previous_price = current_price, current_price = ?, total_volume = total_volume + ?
                WHERE stock_code = ?
                """, next, volume, code);
            jdbc.update("""
                INSERT INTO stock_price_history (stock_id, price)
                SELECT id, ? FROM stocks WHERE stock_code = ?
                """, next, code);
        recordDailyTrade(code, next, volume);
    }

    private void moveToPrice(String code, long price, long volume) {
        long next = Math.max(100, price);
        seedDailySummaries();
        jdbc.update("UPDATE stocks SET previous_price = current_price, current_price = ?, total_volume = total_volume + ? WHERE stock_code = ?", next, volume, code);
        jdbc.update("INSERT INTO stock_price_history (stock_id, price) SELECT id, ? FROM stocks WHERE stock_code = ?", next, code);
        recordDailyTrade(code, next, volume);
    }

    private void recordDailyTrade(String code, long price, long volume) {
        jdbc.update("""
                INSERT INTO daily_market_summaries (stock_id, trading_date, open_price, close_price, total_volume)
                SELECT id, CURRENT_DATE, ?, ?, ? FROM stocks WHERE stock_code = ?
                ON DUPLICATE KEY UPDATE close_price = VALUES(close_price), total_volume = daily_market_summaries.total_volume + VALUES(total_volume)
                """, price, price, Math.max(0, volume), code);
    }

    private void moveBotPrice(String code, long requestedPrice, long volume) {
        Stock current = findStock(code);
        long maxStep = Math.max(tickSize(current.price()), Math.round(current.price() * 0.004));
        long lower = Math.max(100, current.price() - maxStep);
        long upper = current.price() + maxStep;
        long bounded = Math.max(lower, Math.min(upper, requestedPrice));
        moveToPrice(code, bounded, volume);
    }

    private long naturalBotPrice(String code, MatchSummary matched) {
        Stock current = findStock(code);
        double shock = tickRandom.nextGaussian() * 0.0012;
        double previous = botMomentum.getOrDefault(code, 0.0);
        double newsMove = recentNewsBias(code);
        double momentum = Math.max(-0.0025, Math.min(0.0025, previous * 0.72 + shock + newsMove));
        botMomentum.put(code, momentum);
        return Math.max(1, matched.impactPrice() + Math.round(current.price() * momentum));
    }

    /** Recent news is a small bounded drift, so a single headline cannot dominate the book. */
    private double recentNewsBias(String code) {
        Double impact = jdbc.queryForObject("""
                SELECT COALESCE(SUM(impact * GREATEST(0, 1 - TIMESTAMPDIFF(HOUR, COALESCE(e.published_at, e.created_at), CURRENT_TIMESTAMP) / 24.0)), 0)
                FROM market_events e JOIN stocks s ON s.id = e.stock_id
                WHERE e.event_type = 'NEWS' AND s.stock_code = ?
                  AND COALESCE(e.published_at, e.created_at) >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                """, Double.class, code);
        double normalized = impact == null ? 0 : impact * 0.0005;
        return Math.max(-0.006, Math.min(0.006, normalized));
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
        long assetValue = positions.stream().mapToLong(Position::marketValue).sum();
        return new Portfolio(cash, assetValue, cash + reservedCash + unsettledCash + assetValue,
                positions, unsettledCash, unsettledAssetValue, totalFees, realizedProfitLoss);
    }

    private long currentPrice(String code) {
        return findStock(code).price();
    }

    private double changePercent(long current, long previous) {
        if (previous == 0) return 0;
        return Math.round((current - previous) * 10_000.0 / previous) / 100.0;
    }
}
