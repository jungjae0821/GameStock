package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.market.OfficialNoticeService.Notice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static com.gamestock.backend.market.OfficialNoticeParser.cut;
import static com.gamestock.backend.market.OfficialNoticeService.SOURCE;

/**
 * {@link OfficialNoticeService}에서 분리한 저장 부분. 수집한 공지를 market_events에 넣거나 고치고,
 * 출처를 처음 수집할 때는 지난 글을 가치 계산에서 보관 처리한다. Spring 빈이 아니며 서비스가
 * 만든다. 글 하나마다 트랜잭션을 따로 여는 방식은 서비스에 있을 때와 같다.
 */
final class OfficialNoticeStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final NewsFeedService newsFeed;
    private final TransactionTemplate transaction;

    OfficialNoticeStore(JdbcTemplate jdbc, NewsFeedService newsFeed, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.newsFeed = newsFeed;
        this.transaction = transaction;
    }

    /** {@link OfficialNoticeService#save}의 실제 구현. 반환값은 새로 넣은 글 수. */
    int save(String stockCode, String linkPrefix, List<Notice> notices) {
        boolean backfill = Boolean.FALSE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM market_events e JOIN stocks s ON s.id = e.stock_id
                  WHERE s.stock_code = ? AND e.event_type = 'NEWS' AND e.source = ? AND e.description LIKE ?)
                """, Boolean.class, stockCode, SOURCE, "%" + linkPrefix + "%"));
        int inserted = 0;
        for (Notice notice : notices) {
            Boolean added = transaction.execute(status -> saveOne(stockCode, notice, backfill));
            if (Boolean.TRUE.equals(added)) inserted++;
        }
        return inserted;
    }

    private boolean saveOne(String stockCode, Notice notice, boolean backfill) {
        String text = notice.body().isBlank() ? notice.title() : notice.body().strip();
        String description = cut(text, 500) + "\n출처: " + notice.link();
        double impact = newsFeed.newsImpact(notice.title(), notice.body());
        Timestamp publishedAt = notice.publishedAt() == null ? null : Timestamp.from(notice.publishedAt());
        String excerpt = excerpt(notice);
        // 게시 시각은 처음 저장한 값을 지킨다. 카페 목록은 하루가 지나면 "2시간 전"을 날짜로만
        // 보여 주므로, 다시 읽은 값으로 덮으면 시각이 흔들린다.
        int updated = jdbc.update("""
                UPDATE market_events e JOIN stocks s ON s.id = e.stock_id
                SET e.title = ?, e.description = ?, e.impact = ?, e.published_at = COALESCE(e.published_at, ?),
                    e.excerpt = ?, e.excerpt_checked_at = UTC_TIMESTAMP()
                WHERE e.event_type = 'NEWS' AND e.source = ? AND s.stock_code = ? AND e.description LIKE ?
                """, notice.title(), description, impact, publishedAt, excerpt, SOURCE, stockCode,
                "%" + notice.link());
        if (updated > 0) return false;

        List<Map<String, Object>> stock = jdbc.queryForList(
                "SELECT id, current_price FROM stocks WHERE stock_code = ?", stockCode);
        if (stock.isEmpty()) return false;
        long stockId = ((Number) stock.get(0).get("id")).longValue();
        Number price = (Number) stock.get(0).get("current_price");
        if (price == null || price.longValue() <= 0) return false;

        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO market_events (stock_id, event_type, source, title, description, impact,
                                               published_at, price_at_publish, excerpt, excerpt_checked_at)
                    VALUES (?, 'NEWS', ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, stockId);
            statement.setString(2, SOURCE);
            statement.setString(3, notice.title());
            statement.setString(4, description);
            statement.setDouble(5, impact);
            statement.setTimestamp(6, publishedAt);
            statement.setLong(7, price.longValue());
            statement.setString(8, excerpt);
            return statement;
        }, keys);
        if (backfill && keys.getKey() != null) {
            // 처음 수집한 지난 글은 이미 시장에 반영된 소식으로 본다. 보관 표시를 같은
            // 트랜잭션에 넣어 가치 계산이 이 글을 한 번도 새 소식으로 보지 못하게 한다.
            jdbc.update("""
                    INSERT IGNORE INTO market_news_valuation(news_id, stock_id, title, description, impact, published_at, archived)
                    VALUES (?, ?, '', '', 0, COALESCE(?, UTC_TIMESTAMP(3)), TRUE)
                    """, keys.getKey().longValue(), stockId, publishedAt);
        }
        return true;
    }

    private static String excerpt(Notice notice) {
        try {
            return JSON.writeValueAsString(Map.of("v", NewsExcerptService.EXCERPT_VERSION,
                    "paragraphs", OfficialNoticeParser.paragraphs(notice.body()), "url", notice.link()));
        } catch (Exception error) {
            return null;
        }
    }
}
