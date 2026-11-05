package com.gamestock.backend.market;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "market.test.jdbc", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class OfficialNoticeIntegrationTest {
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private OfficialNoticeService service;
    private NewsValuation valuation;
    private String schema;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource(System.getProperty("market.test.jdbc"), "root", "", true);
        jdbc = new JdbcTemplate(source);
        schema = "official_notice_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + schema);
        jdbc.execute("USE " + schema);
        jdbc.execute("CREATE TABLE stocks(id BIGINT PRIMARY KEY, stock_code VARCHAR(10), current_price BIGINT)");
        jdbc.execute("CREATE TABLE market_price_metrics(stock_id BIGINT PRIMARY KEY, hidden_fundamental DOUBLE)");
        jdbc.execute("""
                CREATE TABLE market_events(id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_id BIGINT, event_type VARCHAR(10),
                  source VARCHAR(40) NOT NULL DEFAULT '미디어 보도', title TEXT, description TEXT, impact DOUBLE,
                  published_at TIMESTAMP(3) NULL, price_at_publish BIGINT NULL, excerpt TEXT NULL,
                  excerpt_checked_at TIMESTAMP NULL, created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3))
                """);
        jdbc.update("INSERT INTO stocks VALUES(1, 'GOV', 92000)");
        jdbc.update("INSERT INTO market_price_metrics VALUES(1, 10000)");
        NewsValuation.ensureTables(jdbc);
        valuation = new NewsValuation(jdbc);
        valuation.project(1, "GOV", now);
        service = new OfficialNoticeService(jdbc, new NewsFeedService(jdbc),
                new DataSourceTransactionManager(source), event -> { });
    }

    @AfterEach void cleanup() {
        if (jdbc != null) jdbc.execute("DROP DATABASE " + schema);
        if (source != null) source.destroy();
    }

    private OfficialNoticeService.Notice notice(String id, String title, Instant at) {
        return new OfficialNoticeService.Notice(id, title, "신규 캐릭터와 신규 이벤트가 추가됩니다.\n편의성 개선 항목을 확인해 주세요.", at);
    }

    private double value() {
        return jdbc.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=1", Double.class);
    }

    @Test void firstCollectionIsListedButDoesNotMoveThePrice() {
        assertEquals(1, service.save("GOV", List.of(notice("aaaaaaaa01", "11월 5일 업데이트 공지", now.minusSeconds(3600)))));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM market_events WHERE source='업데이트 노트'", Integer.class));
        assertNotNull(NewsExcerptService.readStored(jdbc.queryForObject("SELECT excerpt FROM market_events", String.class)));
        valuation.project(1, "GOV", now);
        assertEquals(10000, value(), 1e-8);
    }

    @Test void laterNoticeMovesThePriceEvenWithoutTheGameName() {
        service.save("GOV", List.of(notice("aaaaaaaa01", "11월 5일 업데이트 공지", now.minusSeconds(7200))));
        valuation.project(1, "GOV", now);
        assertEquals(1, service.save("GOV", List.of(notice("aaaaaaaa02", "11월 12일 업데이트 공지", now.minusSeconds(60)))));
        valuation.project(1, "GOV", now);
        assertTrue(value() > 10000, "새 공식 공지는 게임 이름이 없어도 주가에 반영돼야 한다");
    }

    @Test void editedTitleUpdatesTheSameRow() {
        service.save("GOV", List.of(notice("aaaaaaaa01", "10월 29일 업데이트 공지", now)));
        assertEquals(0, service.save("GOV", List.of(notice("aaaaaaaa01", "10월 29일 업데이트 공지(10/29 내용 업데이트)", now))));
        assertEquals(List.of("10월 29일 업데이트 공지(10/29 내용 업데이트)"),
                jdbc.queryForList("SELECT title FROM market_events", String.class));
    }
}
