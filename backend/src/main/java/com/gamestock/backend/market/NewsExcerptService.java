package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 뉴스 본문 미리보기. 뉴스는 Google News RSS 링크로만 저장되므로
 * 링크를 언론사 원문 주소로 풀고, 원문 페이지에서 본문 첫 두 문단을 뽑는다.
 * 원문을 세 번 거쳐 읽느라 한 건에 수 초가 걸리므로, 수집된 소식은 백그라운드에서 미리 풀어
 * market_events.excerpt에 저장하고 소식 목록과 함께 내려 준다. 클릭 시 조회도 저장값을 먼저 쓴다.
 */
@Service
public class NewsExcerptService {
    private static final Logger log = LoggerFactory.getLogger(NewsExcerptService.class);

    public record Excerpt(List<String> paragraphs, String url) { }

    private record Cached(Excerpt excerpt, Instant expiresAt) { }

    private static final Pattern GOOGLE_ARTICLE =
            Pattern.compile("^https://news\\.google\\.com/rss/articles/([A-Za-z0-9_-]{20,600})(?:\\?.*)?$");
    private static final Pattern STORED_ARTICLE =
            Pattern.compile("https://news\\.google\\.com/rss/articles/([A-Za-z0-9_-]{20,600})");
    private static final Pattern VIDEO_HOST = Pattern.compile("(?:^|\\.)(?:youtube\\.com|youtu\\.be|tv\\.naver\\.com|chzzk\\.naver\\.com)$", Pattern.CASE_INSENSITIVE);

    /** 저장값 형식 버전. 추출 규칙이 바뀌면 올려서 예전 규칙으로 뽑은 본문을 쓰지 않고 다시 뽑는다. */
    static final int EXCERPT_VERSION = 2;
    private static final Duration HIT_TTL = Duration.ofHours(12);
    private static final Duration MISS_TTL = Duration.ofMinutes(10);
    /* 한 번에 미리 풀 소식 수와 간격. Google에 몰아서 요청하지 않도록 한 건씩 천천히 처리한다. */
    private static final int PREFETCH_BATCH = 20;
    private static final long PREFETCH_PAUSE_MS = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final java.util.concurrent.Semaphore fetchSlots = new java.util.concurrent.Semaphore(4);
    private final ObjectMapper json = JSON;
    private final NewsPageFetcher pages = new NewsPageFetcher(JSON);
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) { return size() > 400; }
    };

    /* 미리 풀기는 봇 매칭과 같은 스케줄러 풀을 쓰지 않도록 전용 스레드에서 돈다. */
    private final ExecutorService prefetcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "news-excerpt-prefetch");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean prefetching = new AtomicBoolean();
    private final AtomicBoolean staleCleared = new AtomicBoolean();

    private org.springframework.context.ApplicationEventPublisher events;

    public NewsExcerptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    public Excerpt excerpt(String link) {
        Matcher article = GOOGLE_ARTICLE.matcher(link == null ? "" : link.trim());
        if (!article.matches()) throw new IllegalArgumentException("지원하지 않는 뉴스 링크입니다.");
        String articleId = article.group(1);

        synchronized (cache) {
            Cached cached = cache.get(articleId);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.excerpt();
        }
        /* 저장된 소식의 링크만 받는다. 아무 주소나 대신 읽어 주는 중계기가 되지 않게 한다. */
        List<String> stored = jdbc.query(
                "SELECT excerpt FROM market_events WHERE event_type = 'NEWS' AND description LIKE ? ORDER BY excerpt IS NULL LIMIT 1",
                (rs, row) -> rs.getString("excerpt"), "%" + articleId + "%");
        if (stored.isEmpty()) throw new NoSuchElementException("저장된 소식이 아닙니다.");
        Excerpt saved = readStored(stored.get(0));
        if (saved != null) {
            remember(articleId, saved);
            return saved;
        }
        Excerpt excerpt = load(articleId);
        store("description LIKE ?", "%" + articleId + "%", excerpt);
        return excerpt;
    }

    /** 수집된 소식 중 아직 본문을 풀지 않은 최신 소식을 미리 풀어 둔다. 앞선 차례가 아직 돌고 있으면 건너뛴다. */
    @Scheduled(fixedDelayString = "${gamestock.news.excerpt-prefetch-ms:60000}",
            initialDelayString = "${gamestock.news.excerpt-initial-delay-ms:45000}")
    public void prefetchPending() {
        if (!prefetching.compareAndSet(false, true)) return;
        try {
            prefetcher.execute(() -> {
                try {
                    prefetchBatch();
                } catch (RuntimeException error) {
                    log.warn("뉴스 본문 미리 풀기 실패", error);
                } finally {
                    prefetching.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            prefetching.set(false);
        }
    }

    private void prefetchBatch() {
        /* 예전 버전 규칙으로 뽑은 본문은 한 번 비워 미리 풀기가 다시 뽑게 한다. 화면에는 이미 쓰이지 않는다. */
        if (staleCleared.compareAndSet(false, true)) {
            int cleared = jdbc.update("UPDATE market_events SET excerpt = NULL, excerpt_checked_at = NULL "
                    + "WHERE event_type = 'NEWS' AND excerpt IS NOT NULL AND excerpt NOT LIKE ?", "%\"v\":" + EXCERPT_VERSION + "%");
            if (cleared > 0) log.info("예전 규칙으로 뽑은 뉴스 본문 {}건을 다시 뽑습니다.", cleared);
            if (cleared > 0 && events != null) events.publishEvent(new NewsChangedEvent());
        }
        /* 원문 주소를 못 푼 소식은 6시간 뒤 다시 시도하고, 2주 지난 소식은 클릭할 때만 푼다. */
        List<Map.Entry<Long, String>> pending = jdbc.query("""
                SELECT id, description FROM market_events
                WHERE event_type = 'NEWS' AND source <> '공식 발표' AND excerpt IS NULL
                  AND (excerpt_checked_at IS NULL OR excerpt_checked_at < UTC_TIMESTAMP() - INTERVAL 6 HOUR)
                  AND COALESCE(published_at, created_at) > UTC_TIMESTAMP() - INTERVAL 14 DAY
                ORDER BY COALESCE(published_at, created_at) DESC, id DESC
                LIMIT ?
                """, (rs, row) -> Map.entry(rs.getLong("id"), String.valueOf(rs.getString("description"))), PREFETCH_BATCH);
        for (Map.Entry<Long, String> row : pending) {
            Matcher article = STORED_ARTICLE.matcher(row.getValue());
            if (!article.find()) {
                store("id = ?", row.getKey(), new Excerpt(List.of(), null));
                continue;
            }
            store("id = ?", row.getKey(), load(article.group(1)));
            try {
                Thread.sleep(PREFETCH_PAUSE_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!pending.isEmpty()) log.info("뉴스 본문 {}건 미리 풀기 완료", pending.size());
    }

    @PreDestroy
    void stop() { prefetcher.shutdownNow(); }

    /**
     * 원문 주소를 풀었으면 결과를 저장해 다시 풀지 않는다. 주소를 못 풀었으면 확인 시각만 남겨
     * 미리 풀기가 같은 소식을 곧바로 반복하지 않게 한다.
     */
    private void store(String where, Object key, Excerpt excerpt) {
        String value = null;
        if (excerpt.url() != null) {
            try {
                value = json.writeValueAsString(Map.of("v", EXCERPT_VERSION, "paragraphs", excerpt.paragraphs(), "url", excerpt.url()));
            } catch (Exception error) {
                log.debug("뉴스 본문 저장값 만들기 실패", error);
            }
        }
        int stored = jdbc.update("UPDATE market_events SET excerpt = ?, excerpt_checked_at = UTC_TIMESTAMP() WHERE event_type = 'NEWS' AND " + where,
                value, key);
        if (stored > 0 && events != null) events.publishEvent(new NewsChangedEvent());
    }

    /** market_events.excerpt에 저장된 값을 읽는다. 없거나 깨졌거나 예전 규칙으로 뽑은 값이면 null. */
    static Excerpt readStored(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(value);
            if (node.path("v").asInt(1) < EXCERPT_VERSION) return null;
            List<String> paragraphs = new ArrayList<>();
            node.path("paragraphs").forEach(paragraph -> paragraphs.add(paragraph.asText()));
            return new Excerpt(List.copyOf(paragraphs), node.path("url").asText(null));
        } catch (Exception error) {
            return null;
        }
    }

    private void remember(String articleId, Excerpt excerpt) {
        Duration ttl = excerpt.paragraphs().isEmpty() ? MISS_TTL : HIT_TTL;
        synchronized (cache) {
            cache.put(articleId, new Cached(excerpt, Instant.now().plus(ttl)));
        }
    }

    /** 원문을 실제로 읽어 첫 두 문단을 뽑는다. 같은 기사가 여러 종목에 걸려 있으면 메모리 캐시를 쓴다. */
    private Excerpt load(String articleId) {
        synchronized (cache) {
            Cached cached = cache.get(articleId);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.excerpt();
        }
        if(!fetchSlots.tryAcquire())throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"뉴스를 읽는 중입니다. 잠시 후 다시 시도해 주세요.");
        String url = null;
        List<String> paragraphs = List.of();
        try {
            url = pages.resolveGoogleNews(articleId);
            /* 영상 링크는 본문이 없으므로 원문 버튼만 남긴다. */
            if (!VIDEO_HOST.matcher(URI.create(url).getHost()).find()) paragraphs = extractParagraphs(pages.fetchPage(url));
        } catch (Exception error) {
            log.debug("뉴스 본문 미리보기 실패: {}", articleId, error);
        } finally { fetchSlots.release(); }
        Excerpt excerpt = new Excerpt(paragraphs, url);
        remember(articleId, excerpt);
        return excerpt;
    }

    /** 본문 추출 규칙은 {@link NewsTextExtractor}에 있다. 테스트가 이 이름으로 부른다. */
    static List<String> extractParagraphs(String html) {
        return NewsTextExtractor.extractParagraphs(html);
    }

    static int elementEnd(String html, int tagStart, int contentStart) {
        return NewsTextExtractor.elementEnd(html, tagStart, contentStart);
    }
}
