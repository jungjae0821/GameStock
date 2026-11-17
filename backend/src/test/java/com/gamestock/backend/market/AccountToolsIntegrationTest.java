package com.gamestock.backend.market;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 MySQL에서 자동 매도와 순자산 기록 SQL을 검증한다. 대출 테스트와 같은 -Dloan.test.* 속성으로 임시 스키마를 만들고 지운다. */
@EnabledIfSystemProperty(named = "loan.test.jdbc", matches = "jdbc:mysql://.*")
class AccountToolsIntegrationTest {
    private HikariDataSource admin, source;
    private String schema;
    private JdbcTemplate db;
    private OrderService orders;
    private StopOrderService stops;
    private AssetHistoryService assets;
    private long human, bot, gi;

    @BeforeEach
    void setUp() {
        HikariConfig root = new HikariConfig();
        root.setJdbcUrl(System.getProperty("loan.test.jdbc"));
        root.setUsername(System.getProperty("loan.test.user"));
        root.setPassword(System.getProperty("loan.test.password"));
        admin = new HikariDataSource(root);
        schema = "tools_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + schema);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("loan.test.jdbc").replaceFirst("/[^/?]*(\\?|$)", "/" + schema + "$1"));
        config.setUsername(root.getUsername());
        config.setPassword(root.getPassword());
        source = new HikariDataSource(config);
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE users (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50), password_hash VARCHAR(100), role VARCHAR(20) NOT NULL DEFAULT 'USER', cash BIGINT NOT NULL, loan_balance BIGINT NOT NULL DEFAULT 0)");
        db.execute("CREATE TABLE stocks (id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_code VARCHAR(10), current_price BIGINT)");
        db.execute("CREATE TABLE market_price_metrics (stock_id BIGINT PRIMARY KEY, mark_price BIGINT)");
        db.execute("CREATE TABLE derivative_positions(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,mark_value BIGINT,status VARCHAR(12))");
        db.execute("CREATE TABLE portfolios (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, stock_id BIGINT, quantity INT, settled_quantity INT)");
        db.execute("CREATE TABLE orders (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, stock_id BIGINT, side VARCHAR(4), status VARCHAR(10), reserved_cash BIGINT, reserved_quantity INT, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE settlements (id BIGINT AUTO_INCREMENT PRIMARY KEY, seller_id BIGINT, gross_amount BIGINT, seller_fee BIGINT, status VARCHAR(10))");
        StopOrderService.ensureTables(db);
        AssetHistoryService.ensureTables(db);
        db.update("INSERT INTO users (username, password_hash, cash) VALUES ('alice', 'GOOGLE', 500000), ('lp', 'BOT', 1000000)");
        human = db.queryForObject("SELECT id FROM users WHERE username='alice'", Long.class);
        bot = db.queryForObject("SELECT id FROM users WHERE username='lp'", Long.class);
        db.update("INSERT INTO stocks (stock_code, current_price) VALUES ('GI', 10000)");
        gi = db.queryForObject("SELECT id FROM stocks WHERE stock_code='GI'", Long.class);
        db.update("INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity) VALUES (?, ?, 30, 30)", human, gi);

        MarketService market = Mockito.mock(MarketService.class);
        Mockito.when(market.ownerAvailable()).thenReturn(true);
        orders = Mockito.mock(OrderService.class);
        // 시장가 매도가 바로 체결된 것처럼 잔고를 줄인다.
        Mockito.when(orders.submit(Mockito.eq(human), Mockito.any())).thenAnswer(call -> {
            MarketModels.OrderRequest request = call.getArgument(1);
            db.update("UPDATE portfolios SET quantity = quantity - ?, settled_quantity = settled_quantity - ? WHERE user_id = ? AND stock_id = ?",
                    request.quantity(), request.quantity(), human, gi);
            return CompletableFuture.completedFuture(new MarketModels.OrderResult("ok", "GI", "SELL", request.quantity(), 9_000, "FILLED",
                    new MarketModels.Portfolio(0, 0, 0, List.of())));
        });
        stops = new StopOrderService(db, market, orders);
        assets = new AssetHistoryService(db, market);
        assets.setClock(Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.close();
        if (admin != null) {
            new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS " + schema);
            admin.close();
        }
    }

    private StopOrderService.StopOrderRequest request(String kind, long trigger, int quantity) {
        return new StopOrderService.StopOrderRequest("gi", kind, trigger, quantity);
    }

    private String status(long id) {
        return db.queryForObject("SELECT status FROM stop_orders WHERE id = ?", String.class, id);
    }

    @Test
    void createRejectsTriggersOnTheWrongSideAndQuantitiesAboveHoldings() {
        assertThrows(IllegalArgumentException.class, () -> stops.create(human, request("STOP_LOSS", 10_000, 10)));
        assertThrows(IllegalArgumentException.class, () -> stops.create(human, request("TAKE_PROFIT", 9_000, 10)));
        assertThrows(IllegalArgumentException.class, () -> stops.create(human, request("STOP_LOSS", 9_000, 31)));
        assertThrows(IllegalArgumentException.class, () -> stops.create(bot, request("STOP_LOSS", 9_000, 1)));
        assertThrows(IllegalArgumentException.class, () -> stops.create(human, request("TRAILING", 9_000, 1)));
        StopOrderService.StopOrder created = stops.create(human, request("STOP_LOSS", 9_000, 10));
        assertEquals("GI", created.stockCode());
        assertEquals("ACTIVE", created.status());
    }

    @Test
    void firingLeftByACrashIsReconciledWithTheActualOrders() {
        long unsent = stops.create(human, request("STOP_LOSS", 9_000, 10)).id();
        long sent = stops.create(human, request("STOP_LOSS", 8_000, 10)).id();
        long fresh = stops.create(human, request("TAKE_PROFIT", 12_000, 10)).id();
        // 두 건은 3분 전에 FIRING으로 바뀐 채 멈췄고, 한 건은 방금 FIRING이 됐다.
        db.update("UPDATE stop_orders SET status = 'FIRING', triggered_at = CURRENT_TIMESTAMP(3) - INTERVAL 3 MINUTE WHERE id IN (?, ?)", unsent, sent);
        db.update("UPDATE stop_orders SET status = 'FIRING', triggered_at = CURRENT_TIMESTAMP(3) WHERE id = ?", fresh);
        // sent는 멈추기 전에 매도 주문까지 나갔다.
        db.update("INSERT INTO orders (user_id, stock_id, side, status, reserved_cash, reserved_quantity, created_at) VALUES (?, ?, 'SELL', 'FILLED', 0, 0, CURRENT_TIMESTAMP(3) - INTERVAL 170 SECOND)", human, gi);
        db.update("UPDATE stop_orders SET triggered_at = CURRENT_TIMESTAMP(3) - INTERVAL 171 SECOND WHERE id = ?", sent);
        // unsent는 그 매도 주문보다 뒤에 FIRING이 됐으므로 그 주문은 unsent의 것이 아니다.
        db.update("UPDATE stop_orders SET triggered_at = CURRENT_TIMESTAMP(3) - INTERVAL 150 SECOND WHERE id = ?", unsent);

        stops.recoverStuck();

        assertEquals("ACTIVE", status(unsent), "주문이 나가지 않았으면 다시 감시한다");
        assertEquals("TRIGGERED", status(sent), "주문이 나갔으면 다시 팔지 않는다");
        assertEquals("FIRING", status(fresh), "아직 접수 중일 수 있는 건은 건드리지 않는다");
        Mockito.verifyNoInteractions(orders);
    }

    @Test
    void stopLossFiresOnceAtTheTriggerAndClosesTheSiblingWhenHoldingsRunOut() {
        long loss = stops.create(human, request("STOP_LOSS", 9_000, 30)).id();
        long profit = stops.create(human, request("TAKE_PROFIT", 12_000, 30)).id();

        stops.watch();
        Mockito.verifyNoInteractions(orders);

        db.update("UPDATE stocks SET current_price = 9000 WHERE id = ?", gi);
        stops.watch();
        stops.watch();
        Mockito.verify(orders, Mockito.times(1)).submit(Mockito.eq(human), Mockito.argThat(order ->
                order.side().equals("SELL") && order.quantity() == 30 && order.orderType().equals("MARKET")));
        assertEquals("TRIGGERED", status(loss));
        assertEquals("CANCELLED", status(profit));
    }

    @Test
    void firingSellsOnlyWhatIsFreeAndFailsWhenNothingIs() {
        long first = stops.create(human, request("TAKE_PROFIT", 11_000, 20)).id();
        db.update("INSERT INTO orders (user_id, stock_id, side, status, reserved_cash, reserved_quantity) VALUES (?, ?, 'SELL', 'OPEN', 0, 25)", human, gi);
        db.update("UPDATE stocks SET current_price = 11000 WHERE id = ?", gi);
        stops.watch();
        Mockito.verify(orders).submit(Mockito.eq(human), Mockito.argThat(order -> order.quantity() == 5));
        assertEquals("TRIGGERED", status(first));

        long second = stops.create(human, request("TAKE_PROFIT", 11_500, 10)).id();
        db.update("UPDATE stocks SET current_price = 12000 WHERE id = ?", gi);
        stops.watch();
        assertEquals("FAILED", status(second));
        Mockito.verify(orders, Mockito.times(1)).submit(Mockito.anyLong(), Mockito.any());
    }

    @Test
    void cancelOnlyTouchesTheOwnersActiveOrder() {
        long id = stops.create(human, request("STOP_LOSS", 9_000, 5)).id();
        assertThrows(IllegalArgumentException.class, () -> stops.cancel(bot, id));
        stops.cancel(human, id);
        assertEquals("CANCELLED", status(id));
        assertThrows(IllegalArgumentException.class, () -> stops.cancel(human, id));
    }

    @Test
    void snapshotsHumansOnceADayAndHistoryEndsWithTheLiveValue() {
        db.update("INSERT INTO asset_snapshots (user_id, snapshot_date, net_asset) VALUES (?, '2026-10-26', 700000), (?, '2026-10-27', 760000)", human, human);
        assets.snapshot();
        db.update("UPDATE users SET cash = 600000 WHERE id = ?", human);
        assets.snapshot();
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM asset_snapshots WHERE user_id = ? AND snapshot_date = '2026-10-28'", Integer.class, human));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM asset_snapshots WHERE user_id = ?", Integer.class, bot));

        // 현금 60만 + GI 30주 × 1만 = 90만
        assertEquals(List.of(new AssetHistoryService.AssetPoint("2026-10-26", 700_000), new AssetHistoryService.AssetPoint("2026-10-27", 760_000),
                new AssetHistoryService.AssetPoint("2026-10-28", 900_000)), assets.history(human, 7));
        assertEquals(List.of(new AssetHistoryService.AssetPoint("2026-10-27", 760_000), new AssetHistoryService.AssetPoint("2026-10-28", 900_000)),
                assets.history(human, 2));
    }
}
