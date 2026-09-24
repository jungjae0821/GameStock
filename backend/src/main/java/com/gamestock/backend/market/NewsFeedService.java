package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConfigurationProperties(prefix = "gamestock.news")
public class NewsFeedService {
    private static final Logger log = LoggerFactory.getLogger(NewsFeedService.class);
    private static final String MEDIA_SOURCE = "미디어 보도";
    private static final String UPDATE_SOURCE = "업데이트 노트";
    private static final Pattern X_PROFILE_TWEET_PATTERN = Pattern.compile(
            "client:VHdlZXQ6([^:]+):details\"\\s*:\\$R\\[\\d+\\]=\\{.*?"
                    + "full_text:\"((?:\\\\.|[^\"\\\\])*)\".*?created_at_ms:(\\d+)",
            Pattern.DOTALL);
    private static final String X_BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter X_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss xx uuuu", Locale.ENGLISH);
    /** Longer phrases come first so a phrase such as "출시 지연" is not
     * double-counted as both a negative phrase and a positive "출시" token. */
    private static final List<WeightedSignal> POSITIVE_SIGNALS = List.of(
            new WeightedSignal("대규모 업데이트", 3), new WeightedSignal("정식 출시", 3),
            new WeightedSignal("신규 업데이트", 2), new WeightedSignal("매출 증가", 3),
            new WeightedSignal("매출 호조", 3), new WeightedSignal("실적 개선", 3),
            new WeightedSignal("이용자 증가", 3), new WeightedSignal("예약자 증가", 2),
            new WeightedSignal("다운로드 증가", 2),
            new WeightedSignal("신규 이벤트", 2), new WeightedSignal("신규 캐릭터", 2),
            new WeightedSignal("콜라보", 2), new WeightedSignal("첫 시연", 2), new WeightedSignal("합류", 2),
            // Headline-native expressions are listed explicitly because many
            // Korean game articles describe a positive outcome without using
            // the shorter tokens below (for example, "좋은 반응" rather than
            // just "성공"). Longer phrases are scored first and then removed.
            new WeightedSignal("매출 신기록", 4), new WeightedSignal("사상 최대 매출", 4),
            new WeightedSignal("이용자 급증", 4), new WeightedSignal("흥행 돌풍", 4),
            new WeightedSignal("성공적 출시", 3), new WeightedSignal("출시 호평", 3),
            new WeightedSignal("긍정적 평가", 3), new WeightedSignal("좋은 반응", 3),
            new WeightedSignal("호평 이어져", 3), new WeightedSignal("인기 급상승", 3),
            new WeightedSignal("글로벌 흥행", 3), new WeightedSignal("기대감 확산", 2),
            // Content launches/openings are positive when they describe a
            // live game's new playable material, not merely an announcement.
            new WeightedSignal("이벤트 개막", 2), new WeightedSignal("이벤트 개최", 2),
            new WeightedSignal("신규 캐릭터 등장", 2), new WeightedSignal("신규 캐릭터 공개", 2),
            new WeightedSignal("신규 보스 등장", 2), new WeightedSignal("신규 보스 공개", 2),
            new WeightedSignal("신규 콘텐츠 공개", 2),
            // Improvements, optimization and stabilization are common
            // positive signals in live-service game headlines.
            new WeightedSignal("서비스 개선", 3), new WeightedSignal("성능 개선", 3),
            new WeightedSignal("품질 개선", 2), new WeightedSignal("편의성 개선", 2),
            new WeightedSignal("문제 해결", 3), new WeightedSignal("버그 수정", 2),
            new WeightedSignal("오류 수정", 2), new WeightedSignal("운영 안정화", 2),
            new WeightedSignal("최적화", 2), new WeightedSignal("리뉴얼", 2),
            new WeightedSignal("개편", 2), new WeightedSignal("보완", 1),
            new WeightedSignal("강화", 1), new WeightedSignal("개선", 1),
            // RSS titles often insert punctuation between the subject and
            // action ("신규 보스·캐릭터 공개"), so keep shorter fallbacks.
            new WeightedSignal("신규 캐릭터", 1), new WeightedSignal("신규 보스", 1),
            new WeightedSignal("캐릭터 등장", 1), new WeightedSignal("보스 공개", 1),
            new WeightedSignal("개막", 1), new WeightedSignal("등장", 1),
            new WeightedSignal("흥행", 2), new WeightedSignal("성공", 2), new WeightedSignal("성공적", 2),
            new WeightedSignal("성장", 2), new WeightedSignal("호평", 2), new WeightedSignal("호재", 2),
            new WeightedSignal("긍정적", 2), new WeightedSignal("인기", 2), new WeightedSignal("돌파", 2),
            new WeightedSignal("기대작", 2), new WeightedSignal("수상", 2),
            new WeightedSignal("출시", 2), new WeightedSignal("업데이트", 1), new WeightedSignal("신작", 1),
            new WeightedSignal("증가", 1), new WeightedSignal("달성", 1), new WeightedSignal("캠페인", 1),
            new WeightedSignal("무료", 1), new WeightedSignal("픽업", 1), new WeightedSignal("시연", 1),
            new WeightedSignal("revenue", 2), new WeightedSignal("growth", 2), new WeightedSignal("success", 2),
            new WeightedSignal("award", 2), new WeightedSignal("launch", 2), new WeightedSignal("popular", 2),
            new WeightedSignal("profit", 2));
    private static final List<WeightedSignal> NEGATIVE_SIGNALS = List.of(
            new WeightedSignal("서비스 종료", 4), new WeightedSignal("서비스 중단", 4),
            new WeightedSignal("개선 요구", 3), new WeightedSignal("개선 필요", 3),
            new WeightedSignal("개선이 필요", 3), new WeightedSignal("개선이 시급", 3),
            new WeightedSignal("개선해야", 3), new WeightedSignal("개선 촉구", 3),
            new WeightedSignal("개선되지", 3),
            new WeightedSignal("출시 실패", 4), new WeightedSignal("출시 취소", 4),
            new WeightedSignal("이벤트 개막 취소", 4), new WeightedSignal("이벤트 개최 취소", 4),
            new WeightedSignal("신규 캐릭터 공개 취소", 4), new WeightedSignal("신규 보스 공개 취소", 4),
            new WeightedSignal("개인정보 유출", 4), new WeightedSignal("데이터 유출", 4),
            new WeightedSignal("불법 프로그램", 4), new WeightedSignal("핵 사용", 3),
            new WeightedSignal("핵 이용", 3), new WeightedSignal("비정상 플레이", 3),
            new WeightedSignal("계정 정지", 3), new WeightedSignal("이용 제한", 3),
            new WeightedSignal("법적 분쟁", 3), new WeightedSignal("서버 다운", 3),
            new WeightedSignal("업데이트 취소", 3), new WeightedSignal("성공하지 못", 3),
            new WeightedSignal("출시하지 못", 3), new WeightedSignal("성장 둔화", 3),
            new WeightedSignal("실적 악화", 3), new WeightedSignal("기대 이하", 3),
            new WeightedSignal("예상 하회", 3), new WeightedSignal("매출 하락", 3),
            new WeightedSignal("매출 감소", 3), new WeightedSignal("이용자 감소", 3),
            new WeightedSignal("이용자 이탈", 3), new WeightedSignal("예약 취소", 2),
            new WeightedSignal("접속 장애", 3), new WeightedSignal("접속 불가", 3), new WeightedSignal("긴급 점검", 3),
            new WeightedSignal("해킹", 4), new WeightedSignal("환불", 2), new WeightedSignal("논란", 2),
            new WeightedSignal("제재", 2), new WeightedSignal("보안", 2), new WeightedSignal("유출", 3),
            new WeightedSignal("피해", 2), new WeightedSignal("과금", 2), new WeightedSignal("맹점", 2),
            new WeightedSignal("사건사고", 2), new WeightedSignal("사고", 1), new WeightedSignal("소송", 3),
            new WeightedSignal("악재", 2), new WeightedSignal("부정적", 2), new WeightedSignal("우려", 2),
            new WeightedSignal("비판", 2), new WeightedSignal("실패", 2), new WeightedSignal("지연", 2),
            new WeightedSignal("장애", 2), new WeightedSignal("점검", 1), new WeightedSignal("삭제", 2),
            new WeightedSignal("취소", 2), new WeightedSignal("중단", 2), new WeightedSignal("하락", 1),
            new WeightedSignal("감소", 1),
            new WeightedSignal("shutdown", 4), new WeightedSignal("decline", 2), new WeightedSignal("delay", 2),
            new WeightedSignal("bug", 2), new WeightedSignal("outage", 3), new WeightedSignal("hack", 4),
            new WeightedSignal("lawsuit", 3), new WeightedSignal("loss", 2));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private boolean enabled;

    private List<String> feeds = List.of();
    private List<String> xAccounts = List.of();

    public NewsFeedService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setFeeds(List<String> feeds) {
        this.feeds = feeds == null ? List.of() : feeds;
    }

    public void setXAccounts(List<String> xAccounts) {
        this.xAccounts = xAccounts == null ? List.of() : xAccounts;
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
        log.info("뉴스 RSS {}개와 공식 X 계정 {}개 수집 시작", feeds.size(), xAccounts.size());
        for (String definition : feeds) {
            try {
                fetchRssFeed(definition);
            } catch (Exception error) {
                log.warn("뉴스 피드 수집 실패: {}", definition, error);
            }
        }
        for (String definition : xAccounts) {
            try {
                fetchXTimeline(definition);
            } catch (Exception error) {
                log.warn("공식 X 업데이트 노트 수집 실패: {}", definition, error);
            }
        }
    }

    private void fetchRssFeed(String definition) throws Exception {
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
        int saved = 0;
        for (NewsItem item : items) {
            if (!NewsRelevance.isRelevant(stockCode, item.title(), item.description(), item.sourceName())) {
                log.debug("관련성 낮은 뉴스 제외: 종목={}, 제목={}", stockCode, item.title());
                continue;
            }
            saveNews(stockCode, item);
            saved++;
        }
        log.info("뉴스 피드 조회 완료: 종목={}, 전체 {}개 중 관련 {}개", stockCode, items.size(), saved);
    }

    /**
     * 공식 X 프로필 페이지에서 계정의 최신 원문을 읽는다.
     * syndication 타임라인은 계정에 따라 오래된 캐시만 반환할 수 있어,
     * 실제 프로필이 내려주는 최신 게시물 데이터를 우선 사용한다.
     */
    private void fetchXTimeline(String definition) throws Exception {
        String[] parts = definition.split("\\|", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) return;

        String stockCode = parts[0].trim().toUpperCase(Locale.ROOT);
        String handle = parts[1].trim();
        String encodedHandle = URLEncoder.encode(handle, StandardCharsets.UTF_8);
        URI endpoint = URI.create("https://x.com/" + encodedHandle);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", X_BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Upgrade-Insecure-Requests", "1")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            log.warn("공식 X 피드가 HTTP {}을 반환했습니다: 종목={}, 계정=@{}", response.statusCode(), stockCode, handle);
            return;
        }

        List<NewsItem> items = parseXProfile(response.body(), handle);
        if (items.isEmpty()) {
            throw new IllegalStateException("X 공식 프로필에서 게시물 데이터를 찾지 못했습니다");
        }

        int saved = 0;
        if (!items.isEmpty()) removeStaleXRows(stockCode);
        for (NewsItem item : items.stream()
                .sorted(Comparator.comparing(NewsItem::publishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .toList()) {
            // Official-account posts are the source of this category. They do
            // not need the Korean article relevance gate, because the account
            // itself is the game-specific relevance boundary.
            saveNews(stockCode, item);
            saved++;
        }
        log.info("공식 X 업데이트 노트 조회 완료: 종목={}, 계정=@{}, 최신 {}개 중 저장 {}개",
                stockCode, handle, Math.min(items.size(), 5), saved);
    }

    /**
     * X 프로필의 React Flight 데이터에서 게시물 ID, 본문, 실제 게시 시각을 추출한다.
     * 화면에 보이는 타임라인 순서가 바뀌어도 Snowflake ID와 created_at_ms를 함께 사용한다.
     */
    private List<NewsItem> parseXProfile(String html, String handle) throws Exception {
        Matcher matcher = X_PROFILE_TWEET_PATTERN.matcher(html);
        List<NewsItem> items = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        while (matcher.find()) {
            String id = new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
            if (!seenIds.add(id)) continue;
            String fullText = decodeHtmlEntities(decodeJsonString(matcher.group(2)));
            if (id.isBlank() || fullText.isBlank()) continue;
            // X 프로필의 created_at_ms는 이 응답에서 한국 현지 벽시각을
            // UTC처럼 담아 내려온다. 먼저 UTC 벽시각으로 읽은 뒤 한국
            // 시간대에 배치해 12:00 게시물이 21:00으로 밀리지 않게 한다.
            Instant publishedAt = parseTweetPublishedAt(matcher.group(3));
            String link = "https://x.com/" + handle + "/status/" + id;
            // 업데이트 노트는 본문 전체를 보존한다. 제목은 목록용 요약으로만
            // 쓰고, 원문은 description으로 화면에 펼쳐 보여준다.
            items.add(new NewsItem(trim(socialTitle(fullText), 150), fullText, link,
                    "@" + handle, publishedAt, UPDATE_SOURCE));
        }
        return items;
    }

    private String decodeJsonString(String escaped) throws Exception {
        return objectMapper.readTree("\"" + escaped + "\"").asText();
    }

    private Instant parseTweetPublishedAt(String createdAtMillis) {
        long wallClockMillis = Long.parseLong(createdAtMillis);
        LocalDateTime localWallClock = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(wallClockMillis), ZoneOffset.UTC);
        return localWallClock.atZone(KST_ZONE).toInstant();
    }

    private void removeStaleXRows(String stockCode) {
        jdbc.update("""
                DELETE FROM market_events
                WHERE stock_id IN (SELECT id FROM stocks WHERE stock_code = ?)
                  AND event_type = 'NEWS' AND source = ?
                  AND description LIKE '%%출처: https://x.com/%%'
                """, stockCode, UPDATE_SOURCE);
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
            String sourceName = text(item, "source");
            String published = firstNonBlank(text(item, "pubDate"), text(item, "published"),
                    text(item, "updated"), text(item, "dc:date"));
            if (!title.isBlank()) {
                result.add(new NewsItem(trim(title, 150), trim(description, 500), link,
                        trim(sourceName, 120), parsePublishedAt(published), MEDIA_SOURCE));
            }
        }
        return result.stream()
                .sorted(Comparator.comparing(NewsItem::publishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                // Fetch more candidates before relevance filtering so an
                // unrelated headline does not push a useful item out.
                // Keep a wider recent window so each stock can fill its five
                // distinct, relevant headlines even when the feed contains
                // several near-duplicate or off-topic results.
                .limit(50)
                .toList();
    }

    private void saveNews(String stockCode, NewsItem item) {
        double impact = newsImpact(item.title(), item.description());
        Timestamp publishedAt = item.publishedAt() == null ? null : Timestamp.from(item.publishedAt());
        String storedDescription = item.description() + "\n출처: " + item.link();
        Long priceAtPublish = jdbc.queryForObject(
                "SELECT current_price FROM stocks WHERE stock_code = ?", Long.class, stockCode);
        if (priceAtPublish == null || priceAtPublish <= 0) return;
        // Recalculate existing rows as the classifier evolves, while keeping
        // the original publication time when an RSS item omits it.
        jdbc.update("""
                UPDATE market_events e JOIN stocks s ON s.id = e.stock_id
                SET e.source = ?, e.description = ?, e.impact = ?, e.published_at = COALESCE(?, e.published_at),
                    e.price_at_publish = COALESCE(e.price_at_publish, ?)
                WHERE e.event_type = 'NEWS' AND s.stock_code = ?
                  AND (e.title = ? OR e.description LIKE CONCAT('%', ?, '%'))
                """, item.source(), storedDescription, impact, publishedAt, priceAtPublish,
                stockCode, item.title(), item.link());
        jdbc.update("""
                INSERT INTO market_events (stock_id, event_type, source, title, description, impact, published_at, price_at_publish)
                SELECT s.id, 'NEWS', ?, ?, ?, ?, ?, ? FROM stocks s
                WHERE s.stock_code = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM market_events e
                      WHERE e.stock_id = s.id AND e.title = ?
                  )
                """, item.source(), item.title(), storedDescription, impact, publishedAt, priceAtPublish, stockCode, item.title());
    }

    /**
     * Convert headline language into a bounded, explainable market impulse.
     * Title signals count twice as strongly as body signals. Negative phrases
     * are scored first and removed from the text, preventing "출시 지연" from
     * being incorrectly rewarded by the positive "출시" keyword. The net
     * score is clamped to -10..10 and zero means the signals cancel out.
     */
    // Package-private for deterministic, fixture-based sentiment tests. This
    // is not exposed as an HTTP API; production callers still use saveNews().
    double newsImpact(String title, String description) {
        String normalizedTitle = normalizeNewsText(title);
        String normalizedDescription = normalizeNewsText(description)
                .replaceAll("출처:\\s*https?://\\S+", " ");
        // Google News often repeats the headline inside the RSS description;
        // remove that copy so the title weight is not accidentally tripled.
        if (!normalizedTitle.isBlank()) normalizedDescription = normalizedDescription.replace(normalizedTitle, " ");
        SignalScore negative = scoreSignals(normalizedTitle, normalizedDescription, NEGATIVE_SIGNALS);
        SignalScore positive = scoreSignals(negative.titleRemainder(), negative.bodyRemainder(), POSITIVE_SIGNALS);
        int net = positive.score() - negative.score();
        return Math.max(-10.0, Math.min(10.0, net));
    }

    private SignalScore scoreSignals(String title, String body, List<WeightedSignal> signals) {
        int score = 0;
        String titleRemainder = title;
        String bodyRemainder = body;
        for (WeightedSignal signal : signals) {
            int titleHits = countOccurrences(titleRemainder, signal.phrase());
            int bodyHits = countOccurrences(bodyRemainder, signal.phrase());
            score += Math.min(2, titleHits) * signal.weight() * 2;
            score += Math.min(2, bodyHits) * signal.weight();
            titleRemainder = titleRemainder.replace(signal.phrase(), " ");
            bodyRemainder = bodyRemainder.replace(signal.phrase(), " ");
        }
        return new SignalScore(score, titleRemainder, bodyRemainder);
    }

    private int countOccurrences(String text, String phrase) {
        int count = 0;
        int from = 0;
        while (true) {
            int found = text.indexOf(phrase, from);
            if (found < 0) return count;
            count++;
            from = found + phrase.length();
        }
    }

    private String normalizeNewsText(String value) {
        return (value == null ? "" : value)
                .replaceAll("<[^>]*>", " ")
                .replaceAll("(?i)&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
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
            return ZonedDateTime.parse(value, X_DATE_FORMATTER).toInstant();
        } catch (DateTimeParseException ignored) { }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) { }
        return null;
    }

    private String trim(String value, int maxLength) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), maxLength));
    }

    private String socialTitle(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String decodeHtmlEntities(String value) {
        return value == null ? "" : value
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private record WeightedSignal(String phrase, int weight) { }
    private record SignalScore(int score, String titleRemainder, String bodyRemainder) { }
    private record NewsItem(String title, String description, String link, String sourceName,
                            Instant publishedAt, String source) { }
}
