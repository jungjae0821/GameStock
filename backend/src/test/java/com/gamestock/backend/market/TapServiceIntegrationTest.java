package com.gamestock.backend.market;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 MySQL에서 탭 베팅 원장을 검증한다. -Dtap.test.jdbc=jdbc:mysql://… 와 계정 속성을 줄 때만 돈다. */
@EnabledIfSystemProperty(named = "tap.test.jdbc", matches = "jdbc:mysql://.*")
class TapServiceIntegrationTest {
    private static final long SLOT = TapService.SLOT_MS;
    private HikariDataSource admin, source;
    private String schema;
    private JdbcTemplate db;
    private TapService tap;
    private ApplicationEventPublisher events;
    private final AtomicLong now = new AtomicLong(2_000_000 * SLOT);
    private long human, bot;

    @BeforeEach
    void setUp() {
        HikariConfig root = new HikariConfig();
        root.setJdbcUrl(System.getProperty("tap.test.jdbc"));
        root.setUsername(System.getProperty("tap.test.user"));
        root.setPassword(System.getProperty("tap.test.password", ""));
        admin = new HikariDataSource(root);
        schema = "tap_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + schema);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("tap.test.jdbc").replaceFirst("/[^/?]*(\\?|$)", "/" + schema + "$1"));
        config.setUsername(root.getUsername());
        config.setPassword(root.getPassword());
        source = new HikariDataSource(config);
        db = new JdbcTemplate(source);
        db.execute("""
                CREATE TABLE users (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50), password_hash VARCHAR(100), cash BIGINT NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        db.execute("CREATE TABLE stocks (id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_code VARCHAR(10), current_price BIGINT)");
        db.execute("""
                CREATE TABLE trades (id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_id BIGINT, buyer_id BIGINT, seller_id BIGINT,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        db.execute("CREATE TABLE attendance_rewards (id BIGINT AUTO_INCREMENT PRIMARY KEY, reward_cash BIGINT)");
        db.execute("CREATE TABLE mission_rewards (id BIGINT AUTO_INCREMENT PRIMARY KEY, reward_cash BIGINT)");
        TapService.ensureTables(db);
        CareerSchema.ensureTables(db);
        db.update("INSERT INTO users (username, password_hash, cash) VALUES ('alice', 'GOOGLE', 1000000), ('lp', 'BOT', 1000000)");
        db.update("INSERT INTO stocks (stock_code, current_price) VALUES ('UMA', 12150), ('GI', 218500)");
        human = db.queryForObject("SELECT id FROM users WHERE username='alice'", Long.class);
        bot = db.queryForObject("SELECT id FROM users WHERE username='lp'", Long.class);
        // Tap betting is paid from the personal wallet, not the trading account.
        PersonalWallet.credit(db, human, "seed", PersonalWallet.MISSION, 1_000_000, "테스트 지갑");
        MarketService market = Mockito.mock(MarketService.class);
        Mockito.when(market.ownerAvailable()).thenReturn(true);
        Mockito.when(market.portfolio(Mockito.anyLong())).thenReturn(new MarketModels.Portfolio(0, 0, 0, java.util.List.of()));
        events = Mockito.mock(ApplicationEventPublisher.class);
        tap = new TapService(db, market, events, new DataSourceTransactionManager(source));
        tap.setClock(new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return Clock.fixed(instant(), zone); }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        });
        tap.tick();
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.close();
        if (admin != null) {
            new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS " + schema);
            admin.close();
        }
    }

    private long cash(long user) { return PersonalWallet.balance(db, user); }
    private long nextSlot() { return (now.get() / SLOT + 1) * SLOT; }

    @Test
    void touchedCellPaysImmediately() {
        long slot = nextSlot();
        var result = tap.place(human, new TapService.PlaceRequest("UMA", slot, 12_200L, 12_210L, 10_000L, null));
        assertEquals(990_000, cash(human));
        double multiplier = result.bet().multiplier();
        assertTrue(multiplier >= TapOdds.MIN_MULTIPLIER);

        now.set(slot + 1_000);
        tap.tick();
        tap.tape().observe("UMA", 12_200, now.get());
        tap.tick();

        Map<String, Object> bet = db.queryForMap("SELECT status, payout FROM tap_bets WHERE id=?", result.bet().id());
        assertEquals("WON", bet.get("status"));
        long payout = ((Number) bet.get("payout")).longValue();
        assertEquals(Math.round(10_000 * multiplier), payout);
        assertEquals(990_000 + payout, cash(human));
        assertEquals(1_000_000L, db.queryForObject("SELECT cash FROM users WHERE id=?", Long.class, human), "the trading account is untouched");
        Mockito.verify(events).publishEvent(Mockito.any(UserMarketEvent.class));
    }

    @Test
    void aWinTheBettorCouldHavePushedIsRefundedNotPaid() {
        long slot = nextSlot();
        var result = tap.place(human, new TapService.PlaceRequest("UMA", slot, 12_200L, 12_210L, 10_000L, null));
        // The bettor buys UMA with the trading account while the bet is live.
        db.update("INSERT INTO trades (stock_id, buyer_id, seller_id, created_at) SELECT id, ?, ?, ? FROM stocks WHERE stock_code = 'UMA'",
                human, bot, java.sql.Timestamp.from(Instant.ofEpochMilli(slot + 500)));

        now.set(slot + 1_000);
        tap.tick();
        tap.tape().observe("UMA", 12_200, now.get());
        tap.tick();

        assertEquals("VOID", db.queryForObject("SELECT status FROM tap_bets WHERE id=?", String.class, result.bet().id()));
        assertEquals(1_000_000, cash(human), "the stake comes back, the multiplier does not");
    }

    @Test
    void untouchedCellLosesAfterItsWindow() {
        long slot = nextSlot();
        var result = tap.place(human, new TapService.PlaceRequest("UMA", slot, 12_200L, 12_210L, 50_000L, null));
        now.set(slot + SLOT - 100);
        tap.tick();
        assertEquals("OPEN", db.queryForObject("SELECT status FROM tap_bets WHERE id=?", String.class, result.bet().id()));
        now.set(slot + SLOT + TapService.SETTLE_GRACE_MS);
        tap.tick();
        assertEquals("LOST", db.queryForObject("SELECT status FROM tap_bets WHERE id=?", String.class, result.bet().id()));
        assertEquals(950_000, cash(human));
    }

    @Test
    void lostPriceHistoryRefundsTheStake() {
        long slot = nextSlot();
        var result = tap.place(human, new TapService.PlaceRequest("UMA", slot, 12_200L, 12_210L, 10_000L, null));
        tap.tape().stop();
        tap.tape().start("UMA", 12_150, slot + 2_000);
        now.set(slot + SLOT + TapService.VOID_AFTER_MS);
        tap.tick();
        assertEquals("VOID", db.queryForObject("SELECT status FROM tap_bets WHERE id=?", String.class, result.bet().id()));
        assertEquals(1_000_000, cash(human));
    }

    @Test
    void rejectsLateDuplicateUnaffordableAndBotBets() {
        long slot = nextSlot();
        now.set(slot - 1_000);
        assertThrows(IllegalArgumentException.class, () -> tap.place(human, new TapService.PlaceRequest("UMA", slot, 12_200L, 12_210L, 10_000L, null)));
        long later = slot + SLOT;
        tap.place(human, new TapService.PlaceRequest("UMA", later, 12_200L, 12_210L, 10_000L, null));
        assertThrows(IllegalArgumentException.class, () -> tap.place(human, new TapService.PlaceRequest("UMA", later, 12_200L, 12_210L, 10_000L, null)));
        assertEquals(990_000, cash(human), "중복으로 걸린 금액은 되돌린다");
        assertThrows(IllegalArgumentException.class, () -> tap.place(human, new TapService.PlaceRequest("UMA", later, 12_205L, 12_215L, 10_000L, null)),
                "칸 경계가 맞지 않는다");
        assertThrows(IllegalArgumentException.class, () -> tap.place(human, new TapService.PlaceRequest("UMA", later, 12_150L, 12_160L, 10_000L, null)),
                "현재가가 든 칸은 걸 수 없다");
        assertThrows(IllegalArgumentException.class, () -> tap.place(human, new TapService.PlaceRequest("UMA", later, 12_220L, 12_230L, 30_000L, null)));
        assertThrows(IllegalArgumentException.class, () -> tap.place(bot, new TapService.PlaceRequest("UMA", later, 12_220L, 12_230L, 10_000L, null)));
        assertThrows(IllegalArgumentException.class, () -> tap.place(human, new TapService.PlaceRequest("CRK", later, 2_900L, 2_905L, 10_000L, null)));
        db.update("UPDATE career_state SET personal_cash = 5000 WHERE user_id=?", human);
        assertThrows(IllegalArgumentException.class, () -> tap.place(human, new TapService.PlaceRequest("UMA", later, 12_220L, 12_230L, 10_000L, null)));
    }

    @Test
    void quotedMultiplierCapsThePayoutAndStaleQuotesAreRejected() {
        long slot = nextSlot() + SLOT;
        var capped = tap.place(human, new TapService.PlaceRequest("UMA", slot, 12_200L, 12_210L, 10_000L, 1.5));
        assertEquals(1.5, capped.bet().multiplier());
        assertThrows(IllegalArgumentException.class, () -> tap.place(human, new TapService.PlaceRequest("UMA", slot, 12_160L, 12_170L, 10_000L, 29.0)));
    }

    @Test
    void stateShowsMyBetsAndTodayTotals() {
        long slot = nextSlot();
        tap.place(human, new TapService.PlaceRequest("UMA", slot, 12_200L, 12_210L, 10_000L, null));
        var state = tap.state("uma", human);
        assertTrue(state.ready());
        assertEquals(12_150, state.price());
        assertEquals(1, state.bets().size());
        assertEquals(10_000, state.today().staked());
        assertTrue(tap.state("UMA", null).bets().isEmpty());
    }
}
