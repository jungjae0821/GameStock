package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.auth.AdminService;
import com.gamestock.backend.auth.AuthService;
import com.gamestock.backend.auth.UserDeletionService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 회원 본인 탈퇴. 일회용 스키마에서 실제 외래 키를 따라 지워지는지 본다. */
@EnabledIfEnvironmentVariable(named = "ADMIN_TEST_JDBC", matches = "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserSelfDeletionIntegrationTest {
    JdbcTemplate root, db;
    HikariDataSource source;
    String schema;
    UserDeletionService deletion;
    long stock;

    @BeforeAll void start() throws Exception {
        String url = System.getenv("ADMIN_TEST_JDBC"), user = System.getenv("ADMIN_TEST_USER"), password = System.getenv("ADMIN_TEST_PASSWORD");
        root = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "self_delete_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE DATABASE " + schema);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url.replaceFirst("/[^/?]*(\\?|$)", "/" + schema + "$1"));
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        source = new HikariDataSource(config);
        db = new JdbcTemplate(source);
        String sql = Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$", "")
                .replaceAll("(?is)CREATE DATABASE[^;]+;", "").replaceAll("(?is)USE gamestock;", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) db.execute(statement);
        var protection = new TradingProtectionService(db);
        var market = new MarketService(mock(ApplicationEventPublisher.class), db, new UserFeatureService(db), protection);
        market.configureTitles(new TitleService(db, new DataSourceTransactionManager(source)));
        market.initializeData();
        StopOrderService.ensureTables(db);

        var orders = mock(OrderService.class);
        when(orders.cancelAll(anyLong())).thenReturn(CompletableFuture.completedFuture(0));
        var support = new AdminSupport(db, mock(AuthService.class), new ObjectMapper().findAndRegisterModules());
        deletion = new UserDeletionService(db, new AdminService(db, mock(AuthService.class)), support, orders,
                new DataSourceTransactionManager(source));
        stock = db.queryForObject("SELECT id FROM stocks WHERE stock_code='UMA'", Long.class);
    }

    @AfterAll void stop() {
        if (source != null) source.close();
        if (root != null && schema != null) root.execute("DROP DATABASE " + schema);
    }

    private long member(String name, String role) {
        db.update("INSERT INTO users(username,password_hash,nickname,google_uid,email,role,cash) VALUES(?,'TEST',?,?,?,?,1000000)",
                name, name, "uid-" + name, name + "@example.test", role);
        return db.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
    }

    private int count(String sql, Object... args) { return db.queryForObject(sql, Integer.class, args); }

    @Test void memberCanDeleteTheirOwnAccountAndItsPersonalData() {
        long id = member("leaver", "USER");
        long other = member("stayer", "USER");
        db.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price) VALUES(?,?,5,5,10000)", id, stock);
        db.update("INSERT INTO user_watchlists(user_id,stock_id) VALUES(?,?)", id, stock);
        db.update("INSERT INTO price_alerts(user_id,stock_id,target_price) VALUES(?,?,12000)", id, stock);
        db.update("INSERT INTO stock_comments(user_id,stock_id,comment_text) VALUES(?,?,'곧 떠나요')", id, stock);
        db.update("INSERT INTO profile_photos(user_id,id,data) VALUES(?,?,?)", id, UUID.randomUUID().toString().replace("-", ""), new byte[]{1, 2, 3});
        db.update("INSERT INTO stop_orders(user_id,stock_id,kind,trigger_price,quantity) VALUES(?,?,'STOP_LOSS',9000,1)", id, stock);

        boolean loginDeleted = deletion.deleteSelf(id, " leaver ");

        // 테스트에는 Firebase가 없다. 로그인 계정 삭제를 못 했다고 알려 브라우저가 대신 지우게 한다.
        assertFalse(loginDeleted);
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE id=?", id));
        for (String table : List.of("portfolios", "user_watchlists", "price_alerts", "stock_comments", "profile_photos", "stop_orders"))
            assertEquals(0, count("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", id), table);
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id=?", other));

        // 감사 기록은 남되, 탈퇴한 회원의 이메일은 남기지 않는다.
        String detail = db.queryForObject("SELECT detail FROM admin_actions WHERE target_user_id=? AND action='SELF_DELETE'", String.class, id);
        assertEquals("leaver", detail);
        String before = db.queryForObject("SELECT before_json FROM admin_audit WHERE action='SELF_DELETE' AND target=?", String.class, String.valueOf(id));
        assertFalse(before.contains("@example.test"), before);
    }

    @Test void wrongNicknameStopsBeforeAnythingIsDeleted() {
        long id = member("careful", "USER");
        db.update("INSERT INTO user_watchlists(user_id,stock_id) VALUES(?,?)", id, stock);
        assertThrows(IllegalArgumentException.class, () -> deletion.deleteSelf(id, "someone-else"));
        assertThrows(IllegalArgumentException.class, () -> deletion.deleteSelf(id, null));
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id=?", id));
        assertEquals(1, count("SELECT COUNT(*) FROM user_watchlists WHERE user_id=?", id));
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE id=? AND trade_banned_until IS NOT NULL", id));
    }

    @Test void adminAccountCannotLeaveOnItsOwn() {
        long id = member("operator", "ADMIN");
        var error = assertThrows(IllegalArgumentException.class, () -> deletion.deleteSelf(id, "operator"));
        assertTrue(error.getMessage().contains("관리자"));
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id=?", id));
    }

    @Test void adminDeletionStillRecordsTheContactForTheAuditTrail() {
        long admin = member("deleter", "ADMIN");
        long id = member("removed", "USER");
        deletion.delete(admin, id, "removed");
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE id=?", id));
        assertEquals("removed · removed@example.test",
                db.queryForObject("SELECT detail FROM admin_actions WHERE target_user_id=? AND action='USER_DELETE'", String.class, id));
    }
}
