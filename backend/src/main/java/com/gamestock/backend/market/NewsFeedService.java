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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@ConfigurationProperties(prefix = "gamestock.news")
public class NewsFeedService {
    private static final Logger log = LoggerFactory.getLogger(NewsFeedService.class);

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
        for (int index = 0; index < Math.min(items.getLength(), 10); index++) {
            Element item = (Element) items.item(index);
            String title = text(item, "title");
            String link = text(item, "link");
            String description = text(item, "description");
            if (!title.isBlank()) result.add(new NewsItem(trim(title, 150), trim(description, 500), link));
        }
        return result;
    }

    private void saveNews(String stockCode, NewsItem item) {
        jdbc.update("""
                INSERT INTO market_events (stock_id, event_type, title, description, impact)
                SELECT s.id, 'NEWS', ?, ?, 0.00 FROM stocks s
                WHERE s.stock_code = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM market_events e
                      WHERE e.stock_id = s.id AND e.title = ?
                  )
                """, item.title(), item.description() + "\n출처: " + item.link(), stockCode, item.title());
    }

    private String text(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private String trim(String value, int maxLength) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), maxLength));
    }

    private record NewsItem(String title, String description, String link) { }
}
