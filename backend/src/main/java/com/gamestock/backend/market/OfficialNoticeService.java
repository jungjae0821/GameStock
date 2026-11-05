package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 게임사 공식 사이트의 공지사항을 "업데이트 노트"로 수집한다. 지금은 니케 한국 공식 사이트
 * (nikke-kr.com/news.html)의 공지사항 탭 하나만 받는다. 이 탭은 Level Infinite 커뮤니티
 * 서버의 공개 JSON API로 목록을 내려 주므로 HTML을 긁지 않고 그 API를 그대로 호출한다.
 *
 * 공식 공지는 언론 기사와 달리 게임 이름이 제목에 없어도 해당 게임의 글이므로 관련성 필터를
 * 거치지 않고, 주가에는 {@link NewsValuation}이 {@link #WEIGHT}배로 반영한다. 처음 수집할 때
 * 이미 올라와 있던 공지는 목록에만 넣고 주가에는 반영하지 않는다.
 */
@Service
@ConfigurationProperties(prefix = "gamestock.news.official")
public class OfficialNoticeService {
    private static final Logger log = LoggerFactory.getLogger(OfficialNoticeService.class);

    static final String SOURCE = "업데이트 노트";
    /** 공식 공지가 같은 크기의 언론 기사보다 주가를 얼마나 더 움직이는지. */
    static final double WEIGHT = 1.5;

    static final String NIKKE_CODE = "GOV";
    static final String NIKKE_DETAIL = "https://nikke-kr.com/newsdetail.html?content_id=";
    private static final String NIKKE_API =
            "https://na-community.playerinfinite.com/api/gpts.information_feeds_svr.InformationFeedsSvr/GetContentByLabel";
    /* 사이트의 NEWS 칼럼(309) 아래 "공지사항" 탭(892). 사이트 개편으로 바뀌면 목록이 비어 돌아온다. */
    private static final String NIKKE_REQUEST =
            "{\"language\":[\"ko\"],\"gameid\":\"16\",\"offset\":0,\"get_num\":10,\"ext_info_type_list\":[0,1,2],"
                    + "\"secondary_label_id\":892,\"primary_label_id\":309}";

    private static final Pattern CONTENT_ID = Pattern.compile("[A-Za-z0-9]{8,64}");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern GREETING = Pattern.compile("^(?:안녕하세요|지휘관)");
    private static final ObjectMapper JSON = new ObjectMapper();

    record Notice(String contentId, String title, String body, Instant publishedAt) {
        String link() { return NIKKE_DETAIL + contentId; }
    }

    private final JdbcTemplate jdbc;
    private final NewsFeedService newsFeed;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private boolean enabled;

    public OfficialNoticeService(JdbcTemplate jdbc, NewsFeedService newsFeed,
                                 PlatformTransactionManager transactions, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.newsFeed = newsFeed;
        this.transaction = new TransactionTemplate(transactions);
        this.events = events;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void fetchOnStartup() {
        fetch();
    }

    /* 시작할 때 한 번 받으므로 첫 예약 실행은 주기 하나 뒤에 돈다. */
    @Scheduled(fixedDelayString = "${gamestock.news.official.refresh-ms:21600000}",
            initialDelayString = "${gamestock.news.official.refresh-ms:21600000}")
    public void fetchScheduled() {
        fetch();
    }

    private void fetch() {
        if (!enabled) return;
        try {
            List<Notice> notices = parse(download());
            int saved = save(NIKKE_CODE, notices);
            log.info("니케 공식 공지 수집 완료: {}개 중 새 글 {}개", notices.size(), saved);
            events.publishEvent(new NewsChangedEvent());
        } catch (Exception error) {
            // 비공개 API라 사이트가 바뀌면 깨질 수 있다. 다른 뉴스 수집에는 영향이 없도록 여기서 끝낸다.
            log.warn("니케 공식 공지 수집 실패", error);
        }
    }

    private String download() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(NIKKE_API))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json;charset=utf-8")
                .header("User-Agent", "GameStock/1.0 news reader")
                .header("X-AreaId", "na")
                .header("X-GameId", "16")
                .header("X-Language", "ko")
                .header("X-Source", "pc_web")
                .POST(HttpRequest.BodyPublishers.ofString(NIKKE_REQUEST))
                .build();
        HttpResponse<String> response = http.send(request, BoundedHttpBody.utf8(1_000_000));
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body();
    }

    static List<Notice> parse(String response) throws Exception {
        JsonNode root = JSON.readTree(response);
        if (root.path("code").asInt(-1) != 0)
            throw new IllegalStateException("API 오류: " + root.path("msg").asText());
        List<Notice> notices = new ArrayList<>();
        for (JsonNode item : root.path("data").path("info_content")) {
            String contentId = item.path("content_id").asText("");
            String title = plain(item.path("title").asText("")).trim();
            if (!CONTENT_ID.matcher(contentId).matches() || title.isEmpty()) continue;
            String body = plain(item.path("content_part").asText(item.path("content_desc").asText("")));
            long seconds = item.path("pub_timestamp").asLong(0);
            notices.add(new Notice(contentId, cut(title, 150), body,
                    seconds > 0 ? Instant.ofEpochSecond(seconds) : null));
        }
        return notices;
    }

    /** 상세 화면 미리보기용 문단. 인사말을 건너뛰고 내용이 있는 줄 두 개를 고른다. */
    static List<String> paragraphs(String body) {
        List<String> result = new ArrayList<>();
        for (String line : body.split("\\n")) {
            String text = line.strip();
            if (text.length() < 15 || GREETING.matcher(text).find()) continue;
            result.add(cut(text, 700));
            if (result.size() == 2) break;
        }
        return result;
    }

    /**
     * 공지 ID로 기존 행을 찾아 고친다. 공지는 게시 뒤에도 제목이 바뀌므로("…(10/29 내용 업데이트)")
     * 제목이 아니라 원문 주소로 같은 글을 알아본다. 반환값은 새로 넣은 글 수.
     */
    int save(String stockCode, List<Notice> notices) {
        boolean backfill = Boolean.FALSE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM market_events e JOIN stocks s ON s.id = e.stock_id
                  WHERE s.stock_code = ? AND e.event_type = 'NEWS' AND e.source = ? AND e.description LIKE ?)
                """, Boolean.class, stockCode, SOURCE, "%" + NIKKE_DETAIL + "%"));
        int inserted = 0;
        for (Notice notice : notices) {
            Boolean added = transaction.execute(status -> saveOne(stockCode, notice, backfill));
            if (Boolean.TRUE.equals(added)) inserted++;
        }
        return inserted;
    }

    private boolean saveOne(String stockCode, Notice notice, boolean backfill) {
        String description = cut(notice.body().strip(), 500) + "\n출처: " + notice.link();
        double impact = newsFeed.newsImpact(notice.title(), notice.body());
        Timestamp publishedAt = notice.publishedAt() == null ? null : Timestamp.from(notice.publishedAt());
        String excerpt = excerpt(notice);
        int updated = jdbc.update("""
                UPDATE market_events e JOIN stocks s ON s.id = e.stock_id
                SET e.title = ?, e.description = ?, e.impact = ?, e.published_at = COALESCE(?, e.published_at),
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
            // 처음 수집한 지난 공지는 이미 시장에 반영된 소식으로 본다. 보관 표시를 같은
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
                    "paragraphs", paragraphs(notice.body()), "url", notice.link()));
        } catch (Exception error) {
            return null;
        }
    }

    /** 원문 주소로 공식 공지인지 판단한다. 가치 계산 표에는 출처 열이 없어 설명의 주소로 알아본다. */
    static boolean isOfficialLink(String link) {
        return link != null && link.startsWith(NIKKE_DETAIL);
    }

    private static String plain(String value) {
        return TAG.matcher(value == null ? "" : value).replaceAll(" ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
