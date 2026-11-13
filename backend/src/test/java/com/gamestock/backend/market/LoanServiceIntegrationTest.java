package com.gamestock.backend.market;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 MySQL에서 대출 SQL을 검증한다. -Dloan.test.jdbc=jdbc:mysql://… 와 계정 속성을 줄 때만 돈다. 임시 스키마를 만들고 지운다. */
@EnabledIfSystemProperty(named = "loan.test.jdbc", matches = "jdbc:mysql://.*")
class LoanServiceIntegrationTest {
    private HikariDataSource admin, source;
    private String schema;
    private JdbcTemplate db;
    private LoanService loans;
    private OrderService orders;
    private long human, bot;

    @BeforeEach
    void setUp() {
        HikariConfig root = new HikariConfig();
        root.setJdbcUrl(System.getProperty("loan.test.jdbc"));
        root.setUsername(System.getProperty("loan.test.user"));
        root.setPassword(System.getProperty("loan.test.password"));
        admin = new HikariDataSource(root);
        schema = "loan_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + schema);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("loan.test.jdbc").replaceFirst("/[^/?]*(\\?|$)", "/" + schema + "$1"));
        config.setUsername(root.getUsername());
        config.setPassword(root.getPassword());
        source = new HikariDataSource(config);
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE users (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50), password_hash VARCHAR(100), role VARCHAR(20) NOT NULL DEFAULT 'USER', cash BIGINT NOT NULL, loan_balance BIGINT NOT NULL DEFAULT 0, loan_interest_on DATE NULL)");
        db.execute("CREATE TABLE stocks (id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_code VARCHAR(10), current_price BIGINT)");
        db.execute("CREATE TABLE market_price_metrics (stock_id BIGINT PRIMARY KEY, mark_price BIGINT)");
        db.execute("CREATE TABLE derivative_positions(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,mark_value BIGINT,status VARCHAR(12),quantity INT DEFAULT 1)");
        db.execute("CREATE TABLE portfolios (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, stock_id BIGINT, quantity INT, settled_quantity INT)");
        db.execute("CREATE TABLE orders (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, stock_id BIGINT, side VARCHAR(4), status VARCHAR(10), reserved_cash BIGINT, reserved_quantity INT)");
        db.execute("CREATE TABLE settlements (id BIGINT AUTO_INCREMENT PRIMARY KEY, seller_id BIGINT, gross_amount BIGINT, seller_fee BIGINT, status VARCHAR(10))");
        db.execute("""
                CREATE TABLE loan_ledger (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, kind VARCHAR(16) NOT NULL,
                  amount BIGINT NOT NULL, balance_after BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        db.update("INSERT INTO users (username, password_hash, cash) VALUES ('alice', 'GOOGLE', 1000000)");
        db.update("INSERT INTO users (username, password_hash, cash) VALUES ('lp', 'BOT', 1000000)");
        human = db.queryForObject("SELECT id FROM users WHERE username='alice'", Long.class);
        bot = db.queryForObject("SELECT id FROM users WHERE username='lp'", Long.class);
        MarketService market = Mockito.mock(MarketService.class);
        Mockito.when(market.portfolio(Mockito.anyLong())).thenReturn(new MarketModels.Portfolio(0, 0, 0, java.util.List.of()));
        orders=Mockito.mock(OrderService.class);
        loans = new LoanService(db, market, orders, Mockito.mock(DerivativeService.class),
                Mockito.mock(ApplicationEventPublisher.class), new DataSourceTransactionManager(source));
        loans.setClock(Clock.fixed(Instant.parse("2026-10-15T03:00:00Z"), ZoneOffset.UTC));
        loans.allowBorrowing(true);
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.close();
        if (admin != null) {
            new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS " + schema);
            admin.close();
        }
    }

    @Test
    void borrowRepayAndInterestKeepCashBalanceAndLedgerInStep() {
        LoanService.LoanStatus start = loans.status(human);
        assertEquals(500_000, start.limit());
        assertEquals(500_000, start.available());

        loans.borrow(human, 300_000L);
        assertEquals(1_300_000, cash(human));
        assertEquals(300_000, balance(human));
        // 순자산은 그대로 100만이므로 한도 50만 중 20만이 남는다.
        assertEquals(200_000, loans.status(human).available());
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class, () -> loans.borrow(human, 200_001L));
        assertTrue(over.getMessage().contains("한도"));

        // 사흘 뒤 이자 정산: 30만 × (1.001³ − 1) = 900.3 → 901
        db.update("UPDATE users SET loan_interest_on = '2026-10-12' WHERE id = ?", human);
        loans.accrueInterest();
        assertEquals(300_901, balance(human));
        loans.accrueInterest();
        assertEquals(300_901, balance(human), "같은 날 두 번 돌아도 이자는 한 번만 붙는다");

        loans.repay(human, 100_000L);
        assertEquals(1_200_000, cash(human));
        assertEquals(200_901, balance(human));
        loans.repay(human, 10_000_000L);
        assertEquals(0, balance(human));
        assertEquals(999_099, cash(human));
        assertNull(db.queryForObject("SELECT loan_interest_on FROM users WHERE id = ?", java.sql.Date.class, human));
        assertEquals(java.util.List.of("BORROW", "INTEREST", "REPAY", "REPAY"),
                db.queryForList("SELECT kind FROM loan_ledger WHERE user_id = ? ORDER BY id", String.class, human));
        assertThrows(IllegalArgumentException.class, () -> loans.repay(human, 1L));
    }

    @Test
    void botsCannotBorrow() {
        assertThrows(IllegalArgumentException.class, () -> loans.borrow(bot, 10_000L));
        assertEquals(0, loans.status(bot).available());
        assertEquals(0, balance(bot));
    }

    @Test void derivativeCollateralPreventsFalseLiquidationAndNegativeFutureMarksAreFloored() {
        loans.borrow(human,500_000L);
        db.update("UPDATE users SET cash=499000 WHERE id=?",human);
        db.update("INSERT INTO derivative_positions(user_id,mark_value,status) VALUES(?,1000000,'OPEN'),(?,-100000,'OPEN')",human,human);
        assertEquals(1_499_000,loans.status(human).grossAsset());
        assertFalse(loans.status(human).warning());
        loans.liquidate(human);
        assertEquals(499_000,cash(human));
        assertEquals(500_000,balance(human));
        Mockito.verifyNoInteractions(orders);
    }

    @Test void cancelledLiquidationOrderDoesNotPreventTryingTheNextHolding() {
        db.update("UPDATE users SET cash=0,loan_balance=500000 WHERE id=?",human);
        for(String code:java.util.List.of("GI","UMA")) {
            db.update("INSERT INTO stocks(stock_code,current_price) VALUES(?,10000)",code);
            long stock=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            db.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity) VALUES(?,?,20,20)",human,stock);
        }
        Mockito.when(orders.submit(Mockito.eq(human),Mockito.any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(Mockito.mock(MarketModels.OrderResult.class)));
        loans.liquidate(human);
        var request=org.mockito.ArgumentCaptor.forClass(MarketModels.OrderRequest.class);
        Mockito.verify(orders,Mockito.times(2)).submit(Mockito.eq(human),request.capture());
        assertEquals(java.util.Set.of("GI","UMA"),request.getAllValues().stream().map(MarketModels.OrderRequest::stockCode).collect(java.util.stream.Collectors.toSet()));
        assertEquals(500_000,balance(human));
    }

    @ParameterizedTest @ValueSource(longs={0,500_000})
    void recoveredOrRepaidAccountKeepsItsOpenOrders(long debt) {
        db.update("UPDATE users SET cash=750000,loan_balance=? WHERE id=?",debt,human);
        db.update("INSERT INTO orders(user_id,side,status,reserved_cash,reserved_quantity) VALUES(?,'BUY','OPEN',10000,0)",human);
        Mockito.when(orders.cancel(Mockito.eq(human),Mockito.anyLong()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        loans.liquidate(human);
        Mockito.verifyNoInteractions(orders);
        assertEquals(750000,cash(human));assertEquals(debt,balance(human));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND status='OPEN'",Integer.class,human));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM loan_ledger",Integer.class));
    }

    @Test void recoveryDuringCancellationPreservesTheRemainingOrders() {
        db.update("UPDATE users SET cash=0,loan_balance=500000 WHERE id=?",human);
        for(int i=0;i<2;i++)
            db.update("INSERT INTO orders(user_id,side,status,reserved_cash,reserved_quantity) VALUES(?,'BUY','OPEN',100000,0)",human);
        Mockito.when(orders.cancel(Mockito.eq(human),Mockito.anyLong())).thenAnswer(call->{
            long id=call.getArgument(1);
            db.update("UPDATE orders SET status='CANCELLED',reserved_cash=0 WHERE id=?",id);
            // Refund plus a committed deposit while the asynchronous cancellation was running.
            db.update("UPDATE users SET cash=700000 WHERE id=?",human);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        loans.liquidate(human);
        Mockito.verify(orders,Mockito.times(1)).cancel(Mockito.eq(human),Mockito.anyLong());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND status='OPEN'",Integer.class,human));
        assertEquals(700000,cash(human));assertEquals(500000,balance(human));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM loan_ledger",Integer.class));
    }

    @Test
    void adminsCannotBorrow() {
        db.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", human);
        assertThrows(IllegalArgumentException.class, () -> loans.borrow(human, 10_000L));
        assertEquals(0, loans.status(human).available());
        assertEquals(0, balance(human));
    }

    @Test
    void warningRatioBlocksNewLoans() {
        loans.borrow(human, 500_000L);
        // 총 자산 150만 → 65만으로 떨어진 상황: 담보비율 130%
        db.update("UPDATE users SET cash = 650000 WHERE id = ?", human);
        LoanService.LoanStatus status = loans.status(human);
        assertTrue(status.warning());
        assertTrue(status.borrowBlocked());
        assertThrows(IllegalArgumentException.class, () -> loans.borrow(human, 10_000L));
    }

    @Test
    void liquidationRepaysFromCashBackToWarningRatio() {
        loans.borrow(human, 500_000L);
        db.update("UPDATE users SET cash = 550000 WHERE id = ?", human); // 110%
        loans.liquidate(human);
        // (55만−x)/(50만−x)=1.4 → x=37.5만
        assertEquals(125_000, balance(human));
        assertEquals(175_000, cash(human));
    }

    @Test
    void borrowingBeforeTheSchedulerChargesOnlyTheOldBalanceForPastDays() {
        loans.borrow(human, 100_000L);
        // 자정이 지났지만 스케줄러가 아직 돌지 않은 상태
        db.update("UPDATE users SET loan_interest_on = '2026-10-14' WHERE id = ?", human);
        loans.borrow(human, 100_000L);
        // 어제치 이자는 기존 10만에만 붙는다: 100
        assertEquals(200_100, balance(human));
        loans.accrueInterest();
        assertEquals(200_100, balance(human), "스케줄러가 같은 날 이자를 다시 붙이지 않는다");
    }

    @Test
    void fullRepaymentPaysInterestThatTheSchedulerHasNotAddedYet() {
        loans.borrow(human, 100_000L);
        db.update("UPDATE users SET loan_interest_on = '2026-10-14' WHERE id = ?", human);
        loans.repay(human, 10_000_000L);
        assertEquals(0, balance(human));
        assertEquals(999_900, cash(human), "미정산 이자 100원도 함께 갚는다");
        assertEquals(java.util.List.of("BORROW", "INTEREST", "REPAY"),
                db.queryForList("SELECT kind FROM loan_ledger WHERE user_id = ? ORDER BY id", String.class, human));
    }

    private long cash(long id) { return db.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, id); }
    private long balance(long id) { return db.queryForObject("SELECT loan_balance FROM users WHERE id = ?", Long.class, id); }
}
