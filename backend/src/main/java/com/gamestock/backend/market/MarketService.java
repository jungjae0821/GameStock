package com.gamestock.backend.market;

import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
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
        jdbc.update("""
                INSERT IGNORE INTO users (username, password_hash, nickname, cash)
                VALUES (?, ?, ?, ?)
                """, DEMO_USERNAME, "demo", "Demo User", 1_000_000L);

        jdbc.update("""
                INSERT IGNORE INTO games (name, developer, genre)
                VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)
                """, "우마무스메 프리티더비", "Cygames", "RPG",
                "블루 아카이브", "Nexon", "액션",
                "승리의 여신: 니케", "ShiftUp", "캐주얼");

        renameExistingStock("NEXA", "UMA", "네사: 크로니클", "우마무스메 프리티더비");
        renameExistingStock("STAR", "BA", "스타라이트 아레나", "블루 아카이브");
        renameExistingStock("MOMO", "GOV", "모모 팜", "승리의 여신: 니케");
        removeExistingStock("VOID", "보이드 러너");

        insertStock("UMA", "우마무스메 프리티더비", 12_450L);
        insertStock("BA", "블루 아카이브", 8_230L);
        insertStock("GOV", "승리의 여신: 니케", 21_430L);

        insertEvent("UMA", "대규모 시즌 업데이트 적용", 8.0);
        insertEvent("BA", "경쟁작 출시 예고", -4.0);
        insertEvent("GOV", "글로벌 누적 이용자 1,000만 달성", 6.0);

        demoUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, DEMO_USERNAME);
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

    private void insertEvent(String code, String title, double impact) {
        jdbc.update("""
            INSERT INTO market_events (stock_id, event_type, title, description, impact)
            SELECT s.id, 'NEWS', ?, ?, ? FROM stocks s
                WHERE s.stock_code = ?
                  AND NOT EXISTS (SELECT 1 FROM market_events e WHERE e.title = ?)
            """, title, title, impact, code, title);
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
                ORDER BY e.id
                """, (rs, row) -> {
            double impact = rs.getDouble("impact");
            return new MarketEvent(rs.getString("stock_code"), rs.getString("title"),
                    (int) Math.round(impact), impact >= 0 ? "positive" : "negative");
        });
    }

    public synchronized Portfolio portfolio() {
        return portfolioUnsafe();
    }

    public synchronized MarketSnapshot snapshot() {
        return new MarketSnapshot(stocks(), portfolioUnsafe(), marketEvents());
    }

    @Transactional
    public synchronized OrderResult order(OrderRequest request) {
        String code = request.stockCode().toUpperCase(Locale.ROOT);
        String side = request.side().toUpperCase(Locale.ROOT);
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new IllegalArgumentException("주문 구분은 BUY 또는 SELL이어야 합니다.");
        }

        Stock stock = findStock(code);
        if (stock == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        long amount = stock.price() * request.quantity();
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ? FOR UPDATE", Long.class, demoUserId);
        Integer currentQuantity = jdbc.queryForObject(
                "SELECT quantity FROM portfolios WHERE user_id = ? AND stock_id = ?", Integer.class,
                demoUserId, stockId(code));
        int quantity = currentQuantity == null ? 0 : currentQuantity;

        if ("BUY".equals(side)) {
            if (cash < amount) throw new IllegalArgumentException("보유 현금이 부족합니다.");
            savePortfolio(code, quantity + request.quantity(), stock.price(), quantity);
            cash -= amount;
        } else {
            if (quantity < request.quantity()) throw new IllegalArgumentException("보유 수량이 부족합니다.");
            savePortfolio(code, quantity - request.quantity(), stock.price(), quantity);
            cash += amount;
        }

        jdbc.update("UPDATE users SET cash = ? WHERE id = ?", cash, demoUserId);
        jdbc.update("""
                INSERT INTO orders (user_id, stock_id, side, order_type, price, quantity, status)
                VALUES (?, ?, ?, 'MARKET', ?, ?, 'FILLED')
                """, demoUserId, stockId(code), side, stock.price(), request.quantity());
        move(code, "BUY".equals(side) ? 0.12 : -0.12, request.quantity());

        MarketSnapshot snapshot = snapshot();
        events.publishEvent(new MarketChangedEvent(snapshot));
        return new OrderResult("주문이 체결되었습니다.", code, side, request.quantity(), stock.price(), snapshot.portfolio());
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

    private void savePortfolio(String code, int nextQuantity, long price, int oldQuantity) {
        long id = stockId(code);
        if (oldQuantity == 0 && nextQuantity > 0) {
            jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, average_price)
                    VALUES (?, ?, ?, ?)
                    """, demoUserId, id, nextQuantity, price);
            return;
        }
        jdbc.update("UPDATE portfolios SET quantity = ? WHERE user_id = ? AND stock_id = ?",
                nextQuantity, demoUserId, id);
    }

    private void move(String code, double movement, int volume) {
        Stock old = findStock(code);
        long next = Math.max(100, Math.round(old.price() * (1 + movement / 100)));
        jdbc.update("""
                UPDATE stocks
                SET previous_price = current_price, current_price = ?, total_volume = total_volume + ?
                WHERE stock_code = ?
                """, next, volume, code);
    }

    private Portfolio portfolioUnsafe() {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, demoUserId);
        List<Position> positions = jdbc.query("""
                SELECT s.stock_code, p.quantity, p.quantity * s.current_price AS market_value
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                WHERE p.user_id = ? AND p.quantity > 0
                ORDER BY p.id
                """, (rs, row) -> new Position(rs.getString("stock_code"),
                rs.getInt("quantity"), rs.getLong("market_value")), demoUserId);
        long assetValue = positions.stream().mapToLong(Position::marketValue).sum();
        return new Portfolio(cash, assetValue, cash + assetValue, positions);
    }

    private double changePercent(long current, long previous) {
        if (previous == 0) return 0;
        return Math.round((current - previous) * 10_000.0 / previous) / 100.0;
    }
}
