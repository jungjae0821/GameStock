package com.gamestock.backend.market;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@ConfigurationProperties(prefix = "gamestock.news")
public class NewsFeedService {
    private static final Logger log = LoggerFactory.getLogger(NewsFeedService.class);
    private static final List<String> POSITIVE_KEYWORDS = List.of(
            "출시", "성공", "흥행", "증가", "성장", "호평", "기대", "달성", "수상", "업데이트", "신작",
            "revenue", "growth", "success", "award", "launch", "popular", "profit");
    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "서비스 종료", "중단", "논란", "하락", "감소", "실패", "지연", "버그", "장애", "해킹", "매출 감소",
            "shutdown", "decline", "delay", "bug", "outage", "hack", "lawsuit", "loss");

    private final JdbcTemplate jdbc;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private boolean enabled;

    private List<String> feeds = List.of();

    public NewsFeedService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setFeeds(List<String> feeds) {
        this.feeds = feeds == null ? List.of() : feeds;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void fetchOnStartup() {
        fetchAllFeeds();
    }

    @Scheduled(fixedDelayString = "${gamestock.news.refresh-ms:600000}", initialDelayString = "${gamestock.news.initial-delay-ms:30000}")
    public void fetchScheduledFeeds() {
        fetchAllFeeds();
    }

    private void fetchAllFeeds() {
        if (!enabled) {
            log.info("뉴스 수집이 비활성화되어 있습니다.");
            return;
        }
        log.info("뉴스 피드 {}개 수집 시작", feeds.size());
        for (String definition : feeds) {
            try {
                fetchFeed(definition);
            } catch (Exception error) {
                log.warn("뉴스 피드 수집 실패: {}", definition, error);
            }
        }
    }

    private void fetchFeed(String definition) throws Exception {
        String[] parts = definition.split("\\|", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) return;

        String stockCode = parts[0].trim().toUpperCase();
        HttpRequest request = HttpRequest.newBuilder(URI.create(parts[1].trim()))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "GameStock/1.0 news reader")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            log.warn("뉴스 피드가 HTTP {}을 반환했습니다: 종목={}", response.statusCode(), stockCode);
            return;
        }

        List<NewsItem> items = parseItems(response.body());
        log.info("뉴스 피드 조회 완료: 종목={}, 제목 {}개", stockCode, items.size());
        for (NewsItem item : items) {
            saveNews(stockCode, item);
        }
    }

    private List<NewsItem> parseItems(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList items = document.getElementsByTagName("item");
        List<NewsItem> result = new ArrayList<>();
        for (int index = 0; index < items.getLength(); index++) {
            Element item = (Element) items.item(index);
            String title = text(item, "title");
            String link = text(item, "link");
            String description = text(item, "description");
            String published = firstNonBlank(text(item, "pubDate"), text(item, "published"),
                    text(item, "updated"), text(item, "dc:date"));
            if (!title.isBlank()) {
                result.add(new NewsItem(trim(title, 150), trim(description, 500), link, parsePublishedAt(published)));
            }
        }
        return result.stream()
                .sorted(Comparator.comparing(NewsItem::publishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .toList();
    }

    private void saveNews(String stockCode, NewsItem item) {
        double impact = newsImpact(item.title(), item.description());
        Timestamp publishedAt = item.publishedAt() == null ? null : Timestamp.from(item.publishedAt());
        if (publishedAt != null) {
            jdbc.update("""
                    UPDATE market_events e JOIN stocks s ON s.id = e.stock_id
                    SET e.published_at = ?
                    WHERE e.event_type = 'NEWS' AND s.stock_code = ? AND e.title = ?
                    """, publishedAt, stockCode, item.title());
        }
        jdbc.update("""
                INSERT INTO market_events (stock_id, event_type, title, description, impact, published_at)
                SELECT s.id, 'NEWS', ?, ?, ?, ? FROM stocks s
                WHERE s.stock_code = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM market_events e
                      WHERE e.stock_id = s.id AND e.title = ?
                  )
                """, item.title(), item.description() + "\n출처: " + item.link(), impact, publishedAt, stockCode, item.title());
    }

    /** Convert headline language into a bounded, explainable market impulse. */
    private double newsImpact(String title, String description) {
        String text = (title + " " + description).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : POSITIVE_KEYWORDS) if (text.contains(keyword)) score++;
        for (String keyword : NEGATIVE_KEYWORDS) if (text.contains(keyword)) score--;
        return Math.max(-10.0, Math.min(10.0, score * 1.5));
    }

    private String text(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private Instant parsePublishedAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) { }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) { }
        return null;
    }

    private String trim(String value, int maxLength) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), maxLength));
    }

    private record NewsItem(String title, String description, String link, Instant publishedAt) { }
}
