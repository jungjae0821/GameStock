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
import java.util.concurrent.ThreadLocalRandom;

import static com.gamestock.backend.market.MarketModels.*;

@Service
public class MarketService {
    private static final String DEMO_USERNAME = "demo";

    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
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
        jdbc.update("""
                INSERT IGNORE INTO users (username, password_hash, nickname, cash)
                VALUES (?, ?, ?, ?)
                """, DEMO_USERNAME, "demo", "Demo User", 1_000_000L);

        jdbc.update("""
                INSERT IGNORE INTO games (name, developer, genre)
                VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)
                """, "우마무스메 프리티더비", "Cygames", "RPG",
                "블루 아카이브", "Nexon", "RPG",
                "승리의 여신: 니케", "ShiftUp", "RPG");

        renameExistingStock("NEXA", "UMA", "네사: 크로니클", "우마무스메 프리티더비");
        renameExistingStock("STAR", "BA", "스타라이트 아레나", "블루 아카이브");
        renameExistingStock("MOMO", "GOV", "모모 팜", "승리의 여신: 니케");
        removeExistingStock("VOID", "보이드 러너");

        insertStock("UMA", "우마무스메 프리티더비", 12_450L);
        insertStock("BA", "블루 아카이브", 8_230L);
        insertStock("GOV", "승리의 여신: 니케", 21_430L);
        seedPriceHistory();

        removeDefaultEvents();

        demoUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, DEMO_USERNAME);
    }

    private void ensureAuthenticationTables() {
        addUserColumnIfMissing("google_uid", "VARCHAR(128) NULL UNIQUE");
        addUserColumnIfMissing("email", "VARCHAR(255) NULL");
        addUserColumnIfMissing("profile_image_url", "VARCHAR(500) NULL");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attendance_rewards (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, rewarded_on DATE NOT NULL,
                  streak_day INT NOT NULL, reward_cash BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uq_attendance_user_day (user_id, rewarded_on),
                  CONSTRAINT fk_attendance_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
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

    private void renameExistingStock(String oldCode, String newCode, String oldName, String newName) {
        jdbc.update("UPDATE games SET name = ? WHERE name = ?", newName, oldName);
        jdbc.update("UPDATE stocks SET stock_code = ? WHERE stock_code = ?", newCode, oldCode);
    }

    private void removeExistingStock(String code, String gameName) {
        jdbc.update("DELETE FROM market_events WHERE stock_id IN (SELECT id FROM stocks WHERE stock_code = ?)", code);
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

    public synchronized Portfolio portfolio(long userId) {
        return portfolioUnsafe(userId);
    }

    public synchronized List<OrderHistory> orderHistory(String code, long userId) {
        return jdbc.query("""
                SELECT o.side, o.quantity, o.price, o.status, o.created_at
                FROM orders o JOIN stocks s ON s.id = o.stock_id
                WHERE o.user_id = ? AND s.stock_code = ?
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 20
                """, (rs, row) -> new OrderHistory(
                rs.getString("side"),
                rs.getInt("quantity"),
                rs.getLong("price"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant().toString()),
                userId, code.toUpperCase(Locale.ROOT));
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

    public synchronized MarketSnapshot snapshot() {
        return new MarketSnapshot(stocks(), portfolioUnsafe(demoUserId), marketEvents());
    }

    @Transactional
    public synchronized OrderResult order(OrderRequest request, long userId) {
        String code = request.stockCode().toUpperCase(Locale.ROOT);
        String side = request.side().toUpperCase(Locale.ROOT);
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new IllegalArgumentException("주문 구분은 BUY 또는 SELL이어야 합니다.");
        }

        Stock stock = findStock(code);
        if (stock == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        long amount = stock.price() * request.quantity();
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
        Integer currentQuantity = jdbc.query(
            "SELECT quantity FROM portfolios WHERE user_id = ? AND stock_id = ?",
            (rs, row) -> rs.getInt("quantity"), userId, stockId(code))
            .stream().findFirst().orElse(0);
        int quantity = currentQuantity;

        if ("BUY".equals(side)) {
            if (cash < amount) throw new IllegalArgumentException("보유 현금이 부족합니다.");
            savePortfolio(code, quantity + request.quantity(), stock.price(), userId);
            cash -= amount;
        } else {
            if (quantity < request.quantity()) throw new IllegalArgumentException("보유 수량이 부족합니다.");
            savePortfolio(code, quantity - request.quantity(), stock.price(), userId);
            cash += amount;
        }

        jdbc.update("UPDATE users SET cash = ? WHERE id = ?", cash, userId);
        jdbc.update("""
                INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, status)
                VALUES (?, ?, ?, 'MARKET', ?, ?, 'FILLED')
                """, userId, stockId(code), side, stock.price(), request.quantity());
        move(code, "BUY".equals(side) ? 0.12 : -0.12, request.quantity());

        MarketSnapshot snapshot = snapshot();
        events.publishEvent(new MarketChangedEvent(snapshot));
        return new OrderResult("주문이 체결되었습니다.", code, side, request.quantity(), stock.price(), portfolioUnsafe(userId));
    }

    @Scheduled(fixedRate = 5_000)
    @Transactional
    public synchronized void simulateBots() {
        List<String> codes = jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
        if (codes.isEmpty()) return;
        String code = codes.get(ThreadLocalRandom.current().nextInt(codes.size()));
        double movement = ThreadLocalRandom.current().nextDouble(-0.18, 0.19);
        move(code, movement, ThreadLocalRandom.current().nextInt(20, 121));
        events.publishEvent(new MarketChangedEvent(snapshot()));
    }

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

    private Portfolio portfolioUnsafe(long userId) {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        List<Position> positions = jdbc.query("""
                SELECT s.stock_code, p.quantity, p.quantity * s.current_price AS market_value
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                WHERE p.user_id = ? AND p.quantity > 0
                ORDER BY p.id
                """, (rs, row) -> new Position(rs.getString("stock_code"),
                rs.getInt("quantity"), rs.getLong("market_value")), userId);
        long assetValue = positions.stream().mapToLong(Position::marketValue).sum();
        return new Portfolio(cash, assetValue, cash + assetValue, positions);
    }

    private double changePercent(long current, long previous) {
        if (previous == 0) return 0;
        return Math.round((current - previous) * 10_000.0 / previous) / 100.0;
    }
}
