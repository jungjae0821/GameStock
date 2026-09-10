package com.gamestock.backend.market;

import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static com.gamestock.backend.market.MarketModels.*;

@Service
public class MarketService {
    private static final long STARTING_CASH = 1_000_000L;
    private static final int MAX_BOT_OPEN_ORDERS_PER_SIDE = 40;
    private static final String DEMO_USERNAME = "demo";

    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Map<String, Double> botMomentum = new HashMap<>();
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
        ensureTradeTable();
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
        normalizeOpenOrderPrices();

        removeDefaultEvents();

        demoUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, DEMO_USERNAME);
    }

    private void ensureAuthenticationTables() {
        addOrderColumnIfMissing();
        addUserColumnIfMissing("google_uid", "VARCHAR(128) NULL UNIQUE");
        addUserColumnIfMissing("email", "VARCHAR(255) NULL");
        addUserColumnIfMissing("profile_image_url", "VARCHAR(500) NULL");
        addUserColumnIfMissing("profile_completed", "BOOLEAN NOT NULL DEFAULT FALSE");
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
            ORDER BY e.created_at DESC, e.id DESC
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
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT 5
                """, (rs, row) -> {
            double impact = rs.getDouble("impact");
            return new MarketEvent(rs.getString("stock_code"), rs.getString("title"),
                    (int) Math.round(impact), impact >= 0 ? "positive" : "negative");
        }, code.toUpperCase(Locale.ROOT));
    }

    public synchronized List<RankingEntry> ranking() {
        List<RankingEntry> entries = jdbc.query("""
                SELECT u.nickname, u.profile_image_url, u.cash,
                       COALESCE((SELECT SUM(o.reserved_cash) FROM orders o WHERE o.user_id = u.id AND o.status = 'OPEN'), 0) AS reserved_cash,
                       COALESCE(SUM(CASE WHEN p.quantity > 0 THEN p.quantity * s.current_price ELSE 0 END), 0) AS asset_value
                FROM users u
                LEFT JOIN portfolios p ON p.user_id = u.id
                LEFT JOIN stocks s ON s.id = p.stock_id
                WHERE u.password_hash <> 'BOT' AND u.username <> 'demo'
                GROUP BY u.id, u.nickname, u.profile_image_url, u.cash
                ORDER BY (u.cash + reserved_cash + asset_value) DESC, u.id ASC
                LIMIT 100
                """, (rs, row) -> {
            long assetValue = rs.getLong("asset_value");
            long cash = rs.getLong("cash");
            long reservedCash = rs.getLong("reserved_cash");
            long totalAsset = cash + reservedCash + assetValue;
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
        expireOrders();
        return portfolioUnsafe(userId);
    }

    public synchronized List<ActiveOrder> activeOrders(long userId) {
        expireOrders();
        return jdbc.query("""
                SELECT o.id, s.stock_code, o.side, o.quantity, o.remaining_quantity, COALESCE(o.price, 0) AS price,
                       o.status, o.order_type, COALESCE(o.reserved_cash, 0) AS reserved_cash,
                       COALESCE(o.reserved_quantity, 0) AS reserved_quantity, o.created_at, o.expires_at
                FROM orders o JOIN stocks s ON s.id = o.stock_id
                WHERE o.user_id = ? AND o.status = 'OPEN'
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 100
                """, (rs, row) -> new ActiveOrder(
                rs.getLong("id"), rs.getString("stock_code"), rs.getString("side"),
                rs.getInt("quantity"), rs.getInt("remaining_quantity"), rs.getLong("price"),
                rs.getString("status"), rs.getString("order_type"), rs.getLong("reserved_cash"),
                rs.getInt("reserved_quantity"), rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant().toString()), userId);
    }

    @Transactional
    public synchronized void cancelOrder(long orderId, long userId) {
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
        expireOrders();
        return jdbc.query("""
                SELECT o.side, o.quantity, o.price, o.status, o.order_type, o.remaining_quantity, o.created_at
                FROM orders o JOIN stocks s ON s.id = o.stock_id
                WHERE o.user_id = ? AND s.stock_code = ?
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 20
                """, (rs, row) -> new OrderHistory(
                rs.getString("side"),
                rs.getInt("quantity"),
                rs.getLong("price"),
                rs.getString("status"),
                rs.getString("order_type"),
                rs.getInt("remaining_quantity"),
                rs.getTimestamp("created_at").toInstant().toString()),
                userId, code.toUpperCase(Locale.ROOT));
    }

    /** 모든 사용자의 익명 체결 내역. 개인별 주문 API와 분리해 공개한다. */
    public synchronized List<PublicTrade> publicTrades(String code) {
        return jdbc.query("""
                SELECT t.aggressor_side AS side, t.quantity, t.price, taker.order_type, t.created_at
                FROM trades t
                JOIN stocks s ON s.id = t.stock_id
                JOIN orders taker ON taker.id = t.taker_order_id
                WHERE s.stock_code = ?
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

    public synchronized OrderBook orderBook(String code) {
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
        long stockId = stockId(code);
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
        if ("BUY".equals(side) && "MARKET".equals(orderType) && cash < amount) throw new IllegalArgumentException("보유 현금이 부족합니다.");
        if ("BUY".equals(side) && "LIMIT".equals(orderType) && cash < amount) throw new IllegalArgumentException("지정가 주문에 필요한 현금이 부족합니다.");
        if ("SELL".equals(side) && availableQuantity(userId, stockId) < requestedQuantity) throw new IllegalArgumentException("보유 수량이 부족합니다.");

        long reservedCash = "BUY".equals(side) && "LIMIT".equals(orderType) ? amount : 0;
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
        return new OrderResult(orderMessage(afterMatch.status()), code, side, requestedQuantity, executedPrice, afterMatch.status(), portfolioUnsafe(userId));
    }

    @Scheduled(fixedRate = 5_000)
    @Transactional
    public synchronized void simulateBots() {
        expireOrders();
        List<String> codes = jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
        if (codes.isEmpty()) return;
        String code = codes.get(ThreadLocalRandom.current().nextInt(codes.size()));
        // 봇은 현재가 양옆에 유동성을 공급하고, 별도의 소량 시장가 주문으로 일부만 소비한다.
        createBotOrder(code, "BUY");
        createBotOrder(code, "SELL");
        // 한 번의 틱 안에서도 양쪽 봇을 모두 실행하되 순서를 섞어 고정된 매수→매도 패턴을 피한다.
        String firstSide = ThreadLocalRandom.current().nextBoolean() ? "BUY" : "SELL";
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
        double offset = ThreadLocalRandom.current().nextDouble(0.002, 0.008);
        double rawPrice = stock.price() * ("BUY".equals(side) ? 1 - offset : 1 + offset);
        long price = "BUY".equals(side) ? floorToTick(rawPrice) : ceilToTick(rawPrice);
        int quantity = ThreadLocalRandom.current().nextInt(5, 31);
        long idStock = stockId(code);
        if ("BUY".equals(side)) {
            long amount = price * quantity;
            int updated = jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ? AND cash >= ?", amount, id, amount);
            if (updated == 0) return;
            jdbc.update("INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, remaining_quantity, reserved_cash, reserved_quantity, status, expires_at) VALUES (?, ?, ?, 'LIMIT', ?, ?, ?, ?, 0, 'OPEN', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 24 HOUR))", id, idStock, side, price, quantity, quantity, amount);
        } else {
            addBotInventory(code, quantity + 100, stock.price(), id);
            jdbc.update("INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, remaining_quantity, reserved_cash, reserved_quantity, status, expires_at) VALUES (?, ?, ?, 'LIMIT', ?, ?, ?, 0, ?, 'OPEN', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 24 HOUR))", id, idStock, side, price, quantity, quantity, quantity);
        }
    }

    private long createBotMarketOrder(String code, String side) {
        long id = ensureBot(code, side);
        Stock stock = findStock(code);
        int quantity = ThreadLocalRandom.current().nextInt(3, 25);
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
        if (buy.reservedCash() == 0 && availableCash(buy.userId()) < tradePrice * quantity) return false;
        return sell.reservedQuantity() > 0 || availableQuantity(sell.userId(), stockId) >= quantity;
    }

    private void settleTrade(long stockId, MatchRow buy, MatchRow sell, MatchRow maker, MatchRow taker, int quantity, long tradePrice) {
        long amount = tradePrice * quantity;
        if (buy.reservedCash() > 0) {
            long reservedChunk = buy.price() * quantity;
            jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reservedChunk - amount, buy.userId());
        } else {
            jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ?", amount, buy.userId());
        }
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", amount, sell.userId());
        adjustQuantity(stockId, buy.userId(), quantity, tradePrice, true);
        adjustQuantity(stockId, sell.userId(), quantity, tradePrice, false);
        jdbc.update("""
                INSERT INTO trades (stock_id, buy_order_id, sell_order_id, buyer_id, seller_id,
                                    maker_order_id, taker_order_id, aggressor_side, quantity, price)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stockId, buy.id(), sell.id(), buy.userId(), sell.userId(), maker.id(), taker.id(), taker.side(), quantity, tradePrice);
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
        if (reservedCash > 0 && "BUY".equals(order.side())) reservedCash = Math.max(0, reservedCash - order.price() * filledQuantity);
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
            if (normalized == order.price()) continue;
            if ("BUY".equals(order.side())) {
                long required;
                try {
                    required = Math.multiplyExact(normalized, order.remainingQuantity());
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
                jdbc.update("UPDATE orders SET price = ?, reserved_cash = ? WHERE id = ?", normalized, required, order.id());
            } else {
                jdbc.update("UPDATE orders SET price = ? WHERE id = ?", normalized, order.id());
            }
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
        int owned = jdbc.query("SELECT quantity FROM portfolios WHERE user_id = ? AND stock_id = ?", (rs, row) -> rs.getInt(1), userId, stockId).stream().findFirst().orElse(0);
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

    private void adjustQuantity(long stockId, long userId, int amount, long price, boolean buy) {
        List<int[]> current = jdbc.query("SELECT quantity, average_price FROM portfolios WHERE user_id = ? AND stock_id = ?", (rs, row) -> new int[]{rs.getInt(1), rs.getInt(2)}, userId, stockId);
        int quantity = current.isEmpty() ? 0 : current.get(0)[0];
        long average = current.isEmpty() ? price : current.get(0)[1];
        int next = buy ? quantity + amount : quantity - amount;
        long nextAverage = buy && next > 0 ? ((average * quantity) + (price * amount)) / next : (next == 0 ? 0 : average);
        if (current.isEmpty()) jdbc.update("INSERT INTO portfolios (user_id, stock_id, quantity, average_price) VALUES (?, ?, ?, ?)", userId, stockId, next, nextAverage);
        else jdbc.update("UPDATE portfolios SET quantity = ?, average_price = ? WHERE user_id = ? AND stock_id = ?", next, nextAverage, userId, stockId);
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

    private Stock findStock(String code) {
        return stocks().stream().filter(stock -> stock.code().equals(code)).findFirst().orElse(null);
    }

    private long stockId(String code) {
        return jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code = ?", Long.class, code);
    }

    private void savePortfolio(String code, int nextQuantity, long price, long userId) {
        long id = stockId(code);
        int updated = jdbc.update("""
                UPDATE portfolios
                SET quantity = ?, average_price = ?
                WHERE user_id = ? AND stock_id = ?
                """, nextQuantity, price, userId, id);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, average_price)
                    VALUES (?, ?, ?, ?)
                    """, userId, id, nextQuantity, price);
        }
    }

    private void addBotInventory(String code, int amount, long price, long userId) {
        long id = stockId(code);
        int current = jdbc.query("SELECT quantity FROM portfolios WHERE user_id = ? AND stock_id = ?",
                (rs, row) -> rs.getInt(1), userId, id).stream().findFirst().orElse(0);
        savePortfolio(code, current + amount, price, userId);
    }

    private void move(String code, double movement, int volume) {
        Stock old = findStock(code);
        long next = Math.max(100, Math.round(old.price() * (1 + movement / 100)));
        jdbc.update("""
                UPDATE stocks
                SET previous_price = current_price, current_price = ?, total_volume = total_volume + ?
                WHERE stock_code = ?
                """, next, volume, code);
            jdbc.update("""
                INSERT INTO stock_price_history (stock_id, price)
                SELECT id, ? FROM stocks WHERE stock_code = ?
                """, next, code);
    }

    private void moveToPrice(String code, long price, long volume) {
        long next = Math.max(100, price);
        jdbc.update("UPDATE stocks SET previous_price = current_price, current_price = ?, total_volume = total_volume + ? WHERE stock_code = ?", next, volume, code);
        jdbc.update("INSERT INTO stock_price_history (stock_id, price) SELECT id, ? FROM stocks WHERE stock_code = ?", next, code);
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
        double shock = ThreadLocalRandom.current().nextGaussian() * 0.0012;
        double previous = botMomentum.getOrDefault(code, 0.0);
        double momentum = Math.max(-0.0025, Math.min(0.0025, previous * 0.72 + shock));
        botMomentum.put(code, momentum);
        return Math.max(1, matched.impactPrice() + Math.round(current.price() * momentum));
    }

    private Portfolio portfolioUnsafe(long userId) {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        List<Position> positions = jdbc.query("""
                SELECT s.stock_code, p.quantity, p.average_price,
                       p.quantity * s.current_price AS market_value,
                       p.quantity * (s.current_price - p.average_price) AS profit_loss
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                WHERE p.user_id = ? AND p.quantity > 0
                ORDER BY p.id
                """, (rs, row) -> new Position(rs.getString("stock_code"),
                rs.getInt("quantity"), rs.getLong("average_price"), rs.getLong("market_value"),
                rs.getLong("profit_loss"), rs.getLong("average_price") == 0 ? 0 :
                        Math.round(rs.getLong("profit_loss") * 10000.0 /
                                (rs.getInt("quantity") * rs.getLong("average_price"))) / 100.0), userId);
        long reservedCash = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_cash), 0) FROM orders WHERE user_id = ? AND status = 'OPEN'", Long.class, userId);
        long assetValue = positions.stream().mapToLong(Position::marketValue).sum();
        return new Portfolio(cash, assetValue, cash + reservedCash + assetValue, positions);
    }

    private double changePercent(long current, long previous) {
        if (previous == 0) return 0;
        return Math.round((current - previous) * 10_000.0 / previous) / 100.0;
    }
}
