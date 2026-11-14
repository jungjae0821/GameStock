package com.gamestock.backend.market;

import com.gamestock.backend.auth.AccountResetService;
import com.gamestock.backend.auth.AuthService;
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
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 MySQL에서 커리어 SQL을 검증한다. -Dcareer.test.jdbc=jdbc:mysql://… 와 계정 속성을 줄 때만 돈다.
 * 임시 스키마를 만들고 지운다.
 */
@EnabledIfSystemProperty(named = "career.test.jdbc", matches = "jdbc:mysql://.*")
class CareerServiceIntegrationTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private HikariDataSource admin, source;
    private String schema;
    private JdbcTemplate db;
    private CareerService career;
    private long alice, stock, provider;

    @BeforeEach
    void setUp() {
        HikariConfig root = new HikariConfig();
        root.setJdbcUrl(System.getProperty("career.test.jdbc"));
        root.setUsername(System.getProperty("career.test.user"));
        root.setPassword(System.getProperty("career.test.password", ""));
        admin = new HikariDataSource(root);
        schema = "career_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + schema);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("career.test.jdbc").replaceFirst("/[^/?]*(\\?|$)", "/" + schema + "$1"));
        config.setUsername(root.getUsername());
        config.setPassword(root.getPassword());
        source = new HikariDataSource(config);
        db = new JdbcTemplate(source);
        createTables();
        // Created before any user exists, so no account is marked legacy.
        CareerSchema.ensureTables(db);
        db.update("INSERT INTO stocks (stock_code, current_price) VALUES ('UMA', 1000)");
        stock = db.queryForObject("SELECT id FROM stocks", Long.class);
        db.update("INSERT INTO market_price_metrics (stock_id, mark_price) VALUES (?, 1000)", stock);
        db.update("INSERT INTO market_book_revisions (stock_id) VALUES (?)", stock);
        alice = user("alice", "GOOGLE", 1_000_000);
        provider = user(LiquidityProviderAccounts.USERNAME, "BOT", 50_000_000);
        career = new CareerService(db, new DataSourceTransactionManager(source), Mockito.mock(ApplicationEventPublisher.class));
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
    void finishingOjtPromotesTraineeToStaffAndTopsUpTheDesk() {
        assertEquals(CareerGrade.TRAINEE, career.grade(alice));
        assertFalse(new FeatureUnlockService(career).unlocked(alice, FeatureUnlockService.Feature.TAP));

        db.update("INSERT INTO user_engagement (user_id, fandom_stock_id) VALUES (?, ?)", alice, stock);
        career.acknowledge(alice, "MARKET");
        long buy = order(alice, "BUY", "LIMIT");
        long sell = order(provider, "SELL", "LIMIT");
        db.update("INSERT INTO trades (stock_id, buy_order_id, sell_order_id, buyer_id, seller_id, quantity, price) VALUES (?, ?, ?, ?, ?, 1, 1000)",
                stock, buy, sell, alice, provider);
        db.update("INSERT INTO trades (stock_id, buy_order_id, sell_order_id, buyer_id, seller_id, quantity, price) VALUES (?, ?, ?, ?, ?, 1, 1000)",
                stock, order(provider, "BUY", "MARKET"), order(alice, "SELL", "MARKET"), provider, alice);
        assertEquals(CareerGrade.TRAINEE, career.grade(alice), "the risk briefing is still missing");

        CareerService.Career result = career.acknowledge(alice, "RISK");

        assertEquals("STAFF", result.grade());
        assertEquals(3_000_000L, cash(alice));
        assertEquals(2_000_000L, db.queryForObject("SELECT SUM(amount) FROM admin_cash_adjustments WHERE user_id = ?", Long.class, alice));
        assertEquals(3_000_000L, result.gradeBase());
        assertEquals("PROMOTE", db.queryForObject("SELECT kind FROM career_events WHERE user_id = ? ORDER BY id DESC LIMIT 1", String.class, alice));
        assertTrue(new FeatureUnlockService(career).unlocked(alice, FeatureUnlockService.Feature.TAP));
        assertFalse(new FeatureUnlockService(career).unlocked(alice, FeatureUnlockService.Feature.FUTURES));
    }

    @Test
    void dailySettlementPaysBonusOnlyAboveTheHighWaterMark() {
        staff(alice, 3_000_000);
        db.update("UPDATE users SET cash = 3300000 WHERE id = ?", alice);
        LocalDate today = LocalDate.now(KST);

        career.settle(alice, today);
        career.settle(alice, today);

        assertEquals(30_000L, PersonalWallet.balance(db, alice), "10% of the 300,000 gain, once");
        assertEquals(3_300_000L, db.queryForObject("SELECT high_water_mark FROM career_state WHERE user_id = ?", Long.class, alice));

        db.update("UPDATE users SET cash = 3200000 WHERE id = ?", alice);
        career.settle(alice, today.plusDays(1));
        db.update("UPDATE users SET cash = 3350000 WHERE id = ?", alice);
        career.settle(alice, today.plusDays(2));

        assertEquals(35_000L, PersonalWallet.balance(db, alice), "only the 50,000 above the old peak pays after a dip");
    }

    @Test
    void tinyFillsDoNotCountTowardPromotion() {
        staff(alice, 3_000_000);
        db.update("UPDATE career_state SET grade_since = NOW() - INTERVAL 5 DAY WHERE user_id = ?", alice);
        // Twenty one-share fills over three days: below 1% of 3,000,000 (30,000) each.
        fills(alice, 20, 1);
        db.update("UPDATE users SET cash = 3300000 WHERE id = ?", alice);
        LocalDate today = LocalDate.now(KST);
        career.settle(alice, today);
        career.settle(alice, today.plusDays(1));

        assertEquals(2, db.queryForObject("SELECT return_streak FROM career_state WHERE user_id = ?", Integer.class, alice));
        assertEquals(CareerGrade.STAFF, career.grade(alice));

        fills(alice, 20, 30);
        career.review(alice);
        assertEquals(CareerGrade.ASSOCIATE, career.grade(alice), "sized fills complete it");
    }

    @Test
    void theReturnMustBeHeldAtTwoSettlementsInARow() {
        staff(alice, 3_000_000);
        db.update("UPDATE career_state SET grade_since = NOW() - INTERVAL 5 DAY WHERE user_id = ?", alice);
        fills(alice, 20, 30);
        db.update("UPDATE users SET cash = 3300000 WHERE id = ?", alice);
        LocalDate today = LocalDate.now(KST);

        career.settle(alice, today);
        career.review(alice);
        assertEquals(CareerGrade.STAFF, career.grade(alice), "touching the target once is not enough");

        db.update("UPDATE users SET cash = 3250000 WHERE id = ?", alice);
        career.settle(alice, today.plusDays(1));
        assertEquals(0, db.queryForObject("SELECT return_streak FROM career_state WHERE user_id = ?", Integer.class, alice),
                "closing below the target breaks the streak");

        db.update("UPDATE users SET cash = 3300000 WHERE id = ?", alice);
        career.settle(alice, today.plusDays(2));
        career.settle(alice, today.plusDays(3));

        assertEquals(CareerGrade.ASSOCIATE, career.grade(alice));
        assertEquals(30_000L, PersonalWallet.balance(db, alice), "the staff gain paid once at 10%, the top-up never");
        assertEquals(10_000_000L, cash(alice));
        assertEquals(0, db.queryForObject("SELECT return_streak FROM career_state WHERE user_id = ?", Integer.class, alice));
    }

    @Test
    void oneStockCannotTakeMoreThanThirtyPercentOfTheDesk() {
        staff(alice, 3_000_000);
        db.update("INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price) VALUES (?, ?, 500, 500, 1000)", alice, stock);

        assertDoesNotThrow(() -> career.requireNewRisk(alice, "UMA", 400, null), "500,000 + 400,000 is within 900,000");
        var error = assertThrows(ResponseStatusException.class, () -> career.requireNewRisk(alice, "UMA", 401, null));
        assertTrue(error.getReason().contains("30%"));
        assertDoesNotThrow(() -> career.requireNewRisk(alice, "UMA", 500, 700L), "a limit order is sized at its own price");

        PersonalWallet.ensureRow(db, provider);
        db.update("UPDATE career_state SET grade = 'TRAINEE' WHERE user_id = ?", alice);
        assertDoesNotThrow(() -> career.requireNewRisk(alice, "UMA", 2_000, null), "trainees learn on the whole 1,000,000");
    }

    @Test
    void adminCashIsNeitherBonusNorPromotionGain() {
        staff(alice, 3_000_000);
        db.update("UPDATE users SET cash = cash + 500000 WHERE id = ?", alice);
        CareerService.shiftForOutsideCash(db, alice, 500_000);

        career.settle(alice, LocalDate.now(KST));

        assertEquals(0L, PersonalWallet.balance(db, alice));
        assertEquals(0.0, career.career(alice).returnPercent(), 1e-9);
    }

    @Test
    void demotionKeepsTheHighWaterMarkSoRecoveringALossPaysNoBonus() {
        staff(alice, 30_000_000);
        db.update("UPDATE career_state SET grade = 'MANAGER' WHERE user_id = ?", alice);
        db.update("UPDATE users SET cash = 23900000 WHERE id = ?", alice);
        career.review(alice);
        assertEquals(CareerGrade.ASSOCIATE, career.grade(alice));

        db.update("UPDATE users SET cash = 30000000 WHERE id = ?", alice);
        career.settle(alice, LocalDate.now(KST));

        assertEquals(0L, PersonalWallet.balance(db, alice), "winning back to the old high is not new gain");
    }

    @Test
    void settlementKeepsADayAlreadyOpenedAndCountsAMissedBreach() {
        staff(alice, 3_000_000);
        LocalDate today = LocalDate.now(KST);
        db.update("UPDATE career_state SET day_open_on = ?, day_open_asset = 3000000 WHERE user_id = ?", Date.valueOf(today), alice);
        db.update("UPDATE users SET cash = 2900000 WHERE id = ?", alice);
        db.update("UPDATE career_state SET last_settled_on = ? WHERE user_id = ?", Date.valueOf(today.minusDays(1)), alice);
        career.settle(alice, today);
        assertEquals(3_000_000L, db.queryForObject("SELECT day_open_asset FROM career_state WHERE user_id = ?", Long.class, alice),
                "a late settlement must not wipe the morning's loss");

        // Yesterday ended 200,000 down (limit 150,000) without another buy attempt.
        db.update("UPDATE career_state SET day_open_on = ?, day_open_asset = 3000000, last_settled_on = ? WHERE user_id = ?",
                Date.valueOf(today), Date.valueOf(today), alice);
        db.update("UPDATE users SET cash = 2800000 WHERE id = ?", alice);
        career.settle(alice, today.plusDays(1));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM career_events WHERE user_id = ? AND kind = 'DAY_LIMIT'", Integer.class, alice));
    }

    @Test
    void unsoldGainsDoNotCountButUnsoldLossesDo() {
        db.update("UPDATE users SET cash = 0 WHERE id = ?", alice);
        db.update("INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price) VALUES (?, ?, 10, 10, 500)", alice, stock);

        assertEquals(10_000L, career.markAsset(alice));
        assertEquals(5_000L, career.confirmedAsset(alice));

        db.update("UPDATE market_price_metrics SET mark_price = 300 WHERE stock_id = ?", stock);
        assertEquals(3_000L, career.confirmedAsset(alice));
    }

    @Test
    void staffCrossingTheLossLineIsRetrainedAndRefilled() {
        staff(alice, 3_000_000);
        db.update("UPDATE users SET cash = 2300000 WHERE id = ?", alice);

        var error = assertThrows(ResponseStatusException.class, () -> career.requireNewRisk(alice));

        assertEquals(403, error.getStatusCode().value());
        assertTrue(error.getReason().contains("재교육"));
        assertEquals(3_000_000L, cash(alice));
        assertEquals("RETRAIN", db.queryForObject("SELECT lock_reason FROM career_state WHERE user_id = ?", String.class, alice));
        assertEquals(1, db.queryForObject("SELECT breach_count FROM career_state WHERE user_id = ?", Integer.class, alice));
        assertThrows(ResponseStatusException.class, () -> career.requireNewRisk(alice), "the lock lasts a day");
    }

    @Test
    void dailyLossLimitLocksNewBuysUntilMidnight() {
        staff(alice, 3_000_000);
        db.update("UPDATE career_state SET day_open_on = ?, day_open_asset = 3000000 WHERE user_id = ?", Date.valueOf(LocalDate.now(KST)), alice);
        db.update("UPDATE users SET cash = 2900000 WHERE id = ?", alice);
        assertDoesNotThrow(() -> career.requireNewRisk(alice), "100,000 is within 5% of 3,000,000");

        db.update("UPDATE users SET cash = 2800000 WHERE id = ?", alice);
        var error = assertThrows(ResponseStatusException.class, () -> career.requireNewRisk(alice));

        assertTrue(error.getReason().contains("일일 손실한도"));
        assertEquals("DAY_LIMIT", db.queryForObject("SELECT lock_reason FROM career_state WHERE user_id = ?", String.class, alice));
        var until = db.queryForObject("SELECT day_locked_until FROM career_state WHERE user_id = ?", java.sql.Timestamp.class, alice).toInstant();
        assertEquals(LocalDate.now(KST).plusDays(1).atStartOfDay(KST).toInstant(), until);
        assertEquals(2_800_000L, cash(alice), "a daily lock never refills the account");
    }

    @Test
    void demotionAboveTheLowerAllocationKeepsTheMoneyAndRebases() {
        staff(alice, 10_000_000);
        db.update("UPDATE career_state SET grade = 'ASSOCIATE' WHERE user_id = ?", alice);
        db.update("UPDATE users SET cash = 7900000 WHERE id = ?", alice);

        career.review(alice);

        assertEquals(CareerGrade.STAFF, career.grade(alice));
        assertEquals(7_900_000L, cash(alice));
        assertEquals(7_900_000L, db.queryForObject("SELECT grade_base FROM career_state WHERE user_id = ?", Long.class, alice));
    }

    @Test
    void legacyAccountsKeepOldAccessUntilMigrated() {
        PersonalWallet.ensureRow(db, alice);
        db.update("UPDATE career_state SET legacy = TRUE WHERE user_id = ?", alice);
        db.update("UPDATE users SET cash = 10 WHERE id = ?", alice);

        assertDoesNotThrow(() -> career.requireNewRisk(alice));
        assertTrue(new FeatureUnlockService(career).unlocked(alice, FeatureUnlockService.Feature.QUANT));
        assertTrue(career.career(alice).legacy());
    }

    @Test
    void walletPaysEachKeyOnceAndRefusesOverdrafts() {
        assertTrue(PersonalWallet.credit(db, alice, "k1", PersonalWallet.MISSION, 10_000, "미션"));
        assertFalse(PersonalWallet.credit(db, alice, "k1", PersonalWallet.MISSION, 10_000, "미션"));
        assertThrows(IllegalArgumentException.class, () -> PersonalWallet.debit(db, alice, "k2", PersonalWallet.TAP, 20_000, "탭"));

        assertEquals(10_000L, PersonalWallet.balance(db, alice));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM personal_ledger WHERE request_key = 'k2'", Integer.class));
        assertEquals(10_000L, db.queryForObject("SELECT balance_after FROM personal_ledger WHERE request_key = 'k1'", Long.class));
    }

    @Test
    void migrationRestartsLegacyMembersAndMovesTheirSharesToTheProvider() {
        long bob = user("bob", "GOOGLE", 2_000_000);
        db.update("UPDATE users SET loan_balance = 500000 WHERE id = ?", bob);
        db.update("INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price) VALUES (?, ?, 10, 10, 800)", bob, stock);
        db.update("INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price) VALUES (?, ?, 5, 5, 1200)", provider, stock);
        db.update("INSERT INTO tap_bets (user_id, stake, status, wallet) VALUES (?, 10000, 'OPEN', FALSE)", bob);
        db.update("INSERT INTO attendance_rewards (user_id, rewarded_on, streak_day, reward_cash) VALUES (?, '2026-11-01', 3, 300000)", bob);
        PersonalWallet.ensureRow(db, bob);
        db.update("UPDATE career_state SET legacy = TRUE WHERE user_id = ?", bob);
        AdminRuntimeControl runtime = Mockito.mock(AdminRuntimeControl.class);
        Mockito.when(runtime.settings()).thenReturn(new AdminRuntimeControl.Settings(true, true, true, null, null, null, 0));
        var migration = new CareerMigrationService(db, new DataSourceTransactionManager(source),
                new AccountResetService(db, Mockito.mock(AuthService.class)), Mockito.mock(AdminSupport.class), runtime);

        var preview = migration.preview();
        assertTrue(preview.dryRun());
        assertEquals(1, preview.members());
        // 2,000,000 cash + 10,000 tap refund + 10 shares at cost 800 (below the 1,000 mark) − 500,000 loan
        assertEquals(1_518_000L, preview.totalNetAsset());
        assertEquals(151_800L, preview.totalSeverance());
        assertEquals(2_000_000L, cash(bob), "preview changes nothing");

        migration.run(1L, "프랍 데스크 출범");

        assertEquals(1_000_000L, cash(bob));
        assertEquals(0L, db.queryForObject("SELECT loan_balance FROM users WHERE id = ?", Long.class, bob));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM portfolios WHERE user_id = ?", Integer.class, bob));
        assertEquals(15, db.queryForObject("SELECT quantity FROM portfolios WHERE user_id = ? AND stock_id = ?", Integer.class, provider, stock));
        assertEquals(1067L, db.queryForObject("SELECT average_price FROM portfolios WHERE user_id = ? AND stock_id = ?", Long.class, provider, stock));
        assertEquals(151_800L, PersonalWallet.balance(db, bob));
        assertEquals("TRAINEE", db.queryForObject("SELECT grade FROM career_state WHERE user_id = ?", String.class, bob));
        assertNotNull(db.queryForObject("SELECT migrated_at FROM career_state WHERE user_id = ?", java.sql.Timestamp.class, bob));
        assertEquals(0L, db.queryForObject("SELECT reward_cash FROM attendance_rewards WHERE user_id = ?", Long.class, bob));
        assertEquals(300_000L, db.queryForObject("SELECT wallet_cash FROM attendance_rewards WHERE user_id = ?", Long.class, bob));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM tap_bets WHERE user_id = ?", Integer.class, bob));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM user_titles WHERE user_id = ? AND title_id = 'founder'", Integer.class, bob));
        assertEquals(0, migration.run(1L, "다시").members(), "a second run finds nobody left");
        assertEquals(CareerGrade.TRAINEE, career.grade(alice), "accounts created after the career are not touched");
    }

    @Test
    void traderRankingPutsGradeFirstAndWealthRankingCountsOnlyTheWallet() {
        long bob = user("bob", "GOOGLE", 1_200_000);
        long carol = user("carol", "GOOGLE", 1_000_000);
        staff(alice, 3_000_000);
        db.update("UPDATE users SET cash = 3150000 WHERE id = ?", alice);
        PersonalWallet.ensureRow(db, bob);
        PersonalWallet.ensureRow(db, carol);
        PersonalWallet.credit(db, carol, "w1", PersonalWallet.BONUS, 500_000, "성과급");
        PersonalWallet.credit(db, alice, "w2", PersonalWallet.BONUS, 100_000, "성과급");
        MarketService market = Mockito.mock(MarketService.class);
        var rankings = new MarketRankingMissions(market, db);

        var traders = rankings.ranking();

        assertEquals(java.util.List.of("alice", "bob", "carol"), traders.stream().map(MarketModels.RankingEntry::nickname).toList(),
                "staff outranks a trainee with a bigger return; trainees then go by return");
        assertEquals("사원", traders.get(0).gradeLabel());
        assertEquals(5.0, traders.get(0).changePercent(), 1e-9, "return on the staff base of 3,000,000");
        assertEquals(20.0, traders.get(1).changePercent(), 1e-9);

        var wealth = rankings.wealthRanking();
        assertEquals(java.util.List.of("carol", "alice"), wealth.stream().map(MarketModels.WealthEntry::nickname).toList());
        assertEquals(500_000L, wealth.get(0).personalCash());
    }

    @Test
    void careerAndReviewStayCheapOnTheHotPaths() {
        staff(alice, 3_000_000);
        fills(alice, 5, 30);
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        javax.sql.DataSource counted = counting(source, counter);
        var countedCareer = new CareerService(new JdbcTemplate(counted), new DataSourceTransactionManager(counted),
                Mockito.mock(ApplicationEventPublisher.class));

        counter.set(0);
        countedCareer.review(alice);
        assertTrue(counter.get() <= 3, "a review with no promotion possible reads only the row and the mark: " + counter.get());

        counter.set(0);
        var view = countedCareer.career(alice);
        assertTrue(counter.get() <= 9, "the view reuses the review's numbers: " + counter.get());
        assertFalse(view.promotion().isEmpty());

        counter.set(0);
        assertNull(countedCareer.acknowledge(alice, "MARKET"), "not a trainee: nothing to record");
        assertTrue(counter.get() <= 2, "a no-op OJT report stays cheap: " + counter.get());
    }

    @Test
    void rankingsAreCachedForAFewSeconds() {
        var millis = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(millis.get()); }
        };
        MarketService market = Mockito.mock(MarketService.class);
        Mockito.when(market.clock()).thenReturn(clock);
        var rankings = new MarketRankingMissions(market, db);
        PersonalWallet.ensureRow(db, alice);
        assertEquals(1, rankings.ranking().size());

        long bob = user("bob", "GOOGLE", 1_000_000);
        PersonalWallet.ensureRow(db, bob);
        millis.addAndGet(MarketRankingMissions.RANKING_CACHE_MS - 1);
        assertEquals(1, rankings.ranking().size(), "within the window the same scan is reused");
        millis.addAndGet(2);
        assertEquals(2, rankings.ranking().size(), "after it the ranking is read again");
    }

    /** A DataSource that counts every statement prepared or created on its connections. */
    private static javax.sql.DataSource counting(javax.sql.DataSource target, java.util.concurrent.atomic.AtomicInteger counter) {
        return new org.springframework.jdbc.datasource.DelegatingDataSource(target) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                java.sql.Connection real = super.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                            String name = method.getName();
                            if (name.equals("prepareStatement") || name.equals("createStatement") || name.equals("prepareCall"))
                                counter.incrementAndGet();
                            try {
                                return method.invoke(real, args);
                            } catch (java.lang.reflect.InvocationTargetException error) {
                                throw error.getCause();
                            }
                        });
            }
        };
    }

    // ── 도움 ────────────────────────────────────────────────

    /** {@code count} fills of {@code quantity} shares at 1,000, spread over the last three days. */
    private void fills(long userId, int count, int quantity) {
        for (int i = 0; i < count; i++)
            db.update("""
                    INSERT INTO trades (stock_id, buy_order_id, sell_order_id, buyer_id, seller_id, quantity, price, created_at)
                    VALUES (?, 0, 0, ?, ?, ?, 1000, NOW() - INTERVAL ? DAY)
                    """, stock, userId, provider, quantity, 1 + i % 3);
    }

    private void staff(long userId, long base) {
        PersonalWallet.ensureRow(db, userId);
        db.update("UPDATE career_state SET grade = 'STAFF', grade_base = ?, high_water_mark = ? WHERE user_id = ?", base, base, userId);
        db.update("UPDATE users SET cash = ? WHERE id = ?", base, userId);
    }

    private long user(String name, String password, long cash) {
        db.update("INSERT INTO users (username, password_hash, nickname, cash) VALUES (?, ?, ?, ?)", name, password, name, cash);
        return db.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
    }

    private long order(long userId, String side, String type) {
        db.update("INSERT INTO orders (user_id, stock_id, side, order_type, status, quantity, remaining_quantity) VALUES (?, ?, ?, ?, 'FILLED', 1, 0)",
                userId, stock, side, type);
        return db.queryForObject("SELECT MAX(id) FROM orders", Long.class);
    }

    private long cash(long userId) {
        return db.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
    }

    private void createTables() {
        db.execute("""
                CREATE TABLE users (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(128) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL,
                  nickname VARCHAR(50) NOT NULL, profile_image_url VARCHAR(500) NULL, role VARCHAR(20) NOT NULL DEFAULT 'USER',
                  cash BIGINT NOT NULL DEFAULT 1000000,
                  loan_balance BIGINT NOT NULL DEFAULT 0, loan_interest_on DATE NULL, account_reset_at TIMESTAMP(3) NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        db.execute("CREATE TABLE stocks (id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_code VARCHAR(10), current_price BIGINT)");
        db.execute("CREATE TABLE market_price_metrics (stock_id BIGINT PRIMARY KEY, mark_price BIGINT)");
        db.execute("CREATE TABLE market_book_revisions (stock_id BIGINT PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0)");
        db.execute("CREATE TABLE market_locks (id TINYINT PRIMARY KEY)");
        db.execute("INSERT INTO market_locks VALUES (1)");
        db.execute("""
                CREATE TABLE portfolios (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, stock_id BIGINT, quantity INT NOT NULL DEFAULT 0,
                  settled_quantity INT NOT NULL DEFAULT 0, average_price BIGINT NOT NULL DEFAULT 0, realized_profit_loss BIGINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uq_portfolio_user_stock (user_id, stock_id))
                """);
        db.execute("""
                CREATE TABLE orders (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, stock_id BIGINT, side VARCHAR(4), order_type VARCHAR(8),
                  status VARCHAR(10), price BIGINT NULL, quantity INT, remaining_quantity INT, reserved_cash BIGINT NOT NULL DEFAULT 0,
                  reserved_quantity INT NOT NULL DEFAULT 0, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))
                """);
        db.execute("""
                CREATE TABLE settlements (id BIGINT AUTO_INCREMENT PRIMARY KEY, buyer_id BIGINT, seller_id BIGINT, stock_id BIGINT, quantity INT,
                  gross_amount BIGINT, seller_fee BIGINT, status VARCHAR(10), settlement_at TIMESTAMP NULL, settled_at TIMESTAMP NULL)
                """);
        db.execute("""
                CREATE TABLE trades (id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_id BIGINT, buy_order_id BIGINT, sell_order_id BIGINT,
                  buyer_id BIGINT, seller_id BIGINT, quantity INT, price BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        db.execute("""
                CREATE TABLE derivative_positions (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, kind VARCHAR(12), status VARCHAR(12),
                  quantity INT NOT NULL DEFAULT 1, mark_value BIGINT NOT NULL DEFAULT 0, margin BIGINT NOT NULL DEFAULT 0,
                  entry_notional BIGINT NOT NULL DEFAULT 0, closed_at TIMESTAMP(3) NULL, close_reason VARCHAR(16) NULL)
                """);
        db.execute("CREATE TABLE derivative_trades (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, kind VARCHAR(12), action VARCHAR(12), created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE market_news_valuation (news_id BIGINT PRIMARY KEY, stock_id BIGINT, published_at TIMESTAMP(3))");
        db.execute("CREATE TABLE user_engagement (user_id BIGINT PRIMARY KEY, fandom_stock_id BIGINT NULL)");
        db.execute("""
                CREATE TABLE admin_cash_adjustments (id BIGINT AUTO_INCREMENT PRIMARY KEY, request_key VARCHAR(80) NOT NULL UNIQUE, admin_id BIGINT,
                  user_id BIGINT, amount BIGINT, reason VARCHAR(300), created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))
                """);
        db.execute("CREATE TABLE admin_cash_epochs (user_id BIGINT PRIMARY KEY, last_adjustment_id BIGINT NOT NULL)");
        db.execute("""
                CREATE TABLE tap_bets (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, stake BIGINT, status VARCHAR(8) NOT NULL DEFAULT 'OPEN',
                  payout BIGINT NOT NULL DEFAULT 0)
                """);
        db.execute("""
                CREATE TABLE attendance_rewards (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, rewarded_on DATE, streak_day INT,
                  reward_cash BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        db.execute("""
                CREATE TABLE mission_rewards (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, mission_id VARCHAR(40), rewarded_on DATE,
                  reward_cash BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        db.execute("CREATE TABLE stop_orders (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT)");
        db.execute("CREATE TABLE quant_projects (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, status VARCHAR(16), updated_at TIMESTAMP(3) NULL)");
        db.execute("CREATE TABLE asset_snapshots (user_id BIGINT, snapshot_date DATE, net_asset BIGINT, PRIMARY KEY (user_id, snapshot_date))");
        db.execute("CREATE TABLE weekly_return_weeks (week_start DATE PRIMARY KEY)");
        db.execute("CREATE TABLE weekly_return_snapshots (week_start DATE, user_id BIGINT, baseline_asset BIGINT, closing_asset BIGINT NULL, PRIMARY KEY (week_start, user_id))");
        db.execute("CREATE TABLE admin_seasons (id BIGINT AUTO_INCREMENT PRIMARY KEY, status VARCHAR(16))");
        db.execute("CREATE TABLE admin_season_entries (season_id BIGINT, user_id BIGINT, opening_asset BIGINT, PRIMARY KEY (season_id, user_id))");
        db.execute("""
                CREATE TABLE user_titles (user_id BIGINT, title_id VARCHAR(40), win_count INT, notified BOOLEAN, acquired_at TIMESTAMP,
                  updated_at TIMESTAMP, PRIMARY KEY (user_id, title_id))
                """);
        db.execute("CREATE TABLE lp_risk_books (stock_id BIGINT PRIMARY KEY, max_inventory INT NOT NULL)");
        db.execute("ALTER TABLE user_titles ADD COLUMN equipped BOOLEAN NOT NULL DEFAULT FALSE");
    }
}
