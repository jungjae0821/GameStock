package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final Pattern GOOGLE_ARTICLE =
            Pattern.compile("^https://news\\.google\\.com/rss/articles/([A-Za-z0-9_-]{20,600})(?:\\?.*)?$");
    private static final Pattern STORED_ARTICLE =
            Pattern.compile("https://news\\.google\\.com/rss/articles/([A-Za-z0-9_-]{20,600})");
    private static final Pattern SIGNATURE = Pattern.compile("data-n-a-sg=\"([^\"]+)\"");
    private static final Pattern TIMESTAMP = Pattern.compile("data-n-a-ts=\"(\\d+)\"");
    private static final Pattern META_CHARSET = Pattern.compile("<meta[^>]+charset=[\"']?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE = Pattern.compile(
            "<!--.*?-->|<(script|style|noscript|figure|figcaption|table|iframe|svg|button|form|aside|nav|footer|header)\\b.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /* 국내 언론사 CMS에서 흔한 본문 컨테이너. 앞에 있을수록 먼저 고른다. */
    private static final Pattern BODY_CONTAINER = Pattern.compile(
            "<[a-z]+[^>]*(?:itemprop=[\"']articleBody[\"']|(?:id|class)=[\"'][^\"']*\\b(?:article-view-content-div|articleBody|article_body|article-body|"
                    + "newsEndContents|dic_area|news_body|news-body|newsct_article|articleText|article_txt|article-txt|news_content|"
                    + "news-content|view_con|viewContent|view_content|article_view|article-view|cont_view|content-body|story-body|"
                    + "entry-content|post-content|article_content|article-content|news_view|news-view|view_text|txt_area)\\b)[^>]*>",
            Pattern.CASE_INSENSITIVE);
    /* 본문임을 직접 밝힌 요소. 클래스 이름으로 찾는 컨테이너는 페이지 전체를 감싸는 경우가 있어 이것을 먼저 쓴다. */
    private static final Pattern ARTICLE_BODY = Pattern.compile("<[a-z]+[^>]*itemprop=[\"']articleBody[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTICLE_TAG = Pattern.compile("<article\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAGRAPH = Pattern.compile("<p\\b[^>]*>(.*?)</p\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINE_BREAKS = Pattern.compile("(?:<br\\s*/?>\\s*){1,}|</div\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_DESCRIPTION = Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"'](?:og:description|description)[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_HOST = Pattern.compile("(?:^|\\.)(?:youtube\\.com|youtu\\.be|tv\\.naver\\.com|chzzk\\.naver\\.com)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");
    /* 바이라인, 저작권, 관련 기사 안내처럼 본문이 아닌 줄. */
    private static final Pattern NOT_BODY = Pattern.compile(
            "^(?:[▶►◆■□●▲△☞ⓒ©]|\\[?사진|\\(사진|Copyright|저작권|무단|관련\\s?기사|기사\\s?제보|구독|많이 본)|"
                    + "(?:기자\\s*[a-zA-Z0-9._%+-]+@|@[a-z0-9-]+\\.(?:co\\.kr|com|kr|net)|\\b\\d[\\d,]*\\s?View\\b)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("nbsp", " "), Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("middot", "·"), Map.entry("hellip", "…"),
            Map.entry("ndash", "–"), Map.entry("mdash", "—"), Map.entry("bull", "•"), Map.entry("times", "×"));

    /** 저장값 형식 버전. 추출 규칙이 바뀌면 올려서 예전 규칙으로 뽑은 본문을 쓰지 않고 다시 뽑는다. */
    static final int EXCERPT_VERSION = 2;
    private static final int PARAGRAPHS = 2;
    private static final int MIN_PARAGRAPH = 30;
    private static final int MAX_PARAGRAPH = 700;
    private static final Duration HIT_TTL = Duration.ofHours(12);
    private static final Duration MISS_TTL = Duration.ofMinutes(10);
    /* 한 번에 미리 풀 소식 수와 간격. Google에 몰아서 요청하지 않도록 한 건씩 천천히 처리한다. */
    private static final int PREFETCH_BATCH = 20;
    private static final long PREFETCH_PAUSE_MS = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final java.util.concurrent.Semaphore fetchSlots = new java.util.concurrent.Semaphore(4);
    private final ObjectMapper json = JSON;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /* Google News 기사 페이지는 같은 도메인 안에서 한 번 리다이렉트한다. 주소가 고정이라 자동으로 따라간다. */
    private final HttpClient google = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
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
            url = resolveGoogleNews(articleId);
            /* 영상 링크는 본문이 없으므로 원문 버튼만 남긴다. */
            if (!VIDEO_HOST.matcher(URI.create(url).getHost()).find()) paragraphs = extractParagraphs(fetchPage(url));
        } catch (Exception error) {
            log.debug("뉴스 본문 미리보기 실패: {}", articleId, error);
        } finally { fetchSlots.release(); }
        Excerpt excerpt = new Excerpt(paragraphs, url);
        remember(articleId, excerpt);
        return excerpt;
    }

    /** Google News 기사 토큰을 언론사 주소로 푼다. 기사 페이지의 서명으로 batchexecute를 한 번 호출한다. */
    private String resolveGoogleNews(String articleId) throws Exception {
        String page = send(HttpRequest.newBuilder(URI.create("https://news.google.com/rss/articles/" + articleId))
                .timeout(Duration.ofSeconds(8)).header("User-Agent", USER_AGENT).GET().build());
        Matcher signature = SIGNATURE.matcher(page);
        Matcher timestamp = TIMESTAMP.matcher(page);
        if (!signature.find() || !timestamp.find()) throw new IllegalStateException("Google News 서명을 찾지 못했습니다.");

        List<Object> request = new ArrayList<>();
        request.add("garturlreq");
        request.add(json.readValue("[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1],"
                + "\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0]", List.class));
        request.add(articleId);
        request.add(Long.parseLong(timestamp.group(1)));
        request.add(signature.group(1));
        String inner = json.writeValueAsString(request);
        String freq = json.writeValueAsString(List.of(List.of(java.util.Arrays.asList("Fbv4je", inner, null, "generic"))));

        String body = send(HttpRequest.newBuilder(URI.create("https://news.google.com/_/DotsSplashUi/data/batchexecute"))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("f.req=" + URLEncoder.encode(freq, StandardCharsets.UTF_8)))
                .build());
        String[] chunks = body.split("\n\n", 3);
        if (chunks.length < 2) throw new IllegalStateException("Google News 응답 형식이 바뀌었습니다.");
        JsonNode payload = json.readTree(json.readTree(chunks[1]).path(0).path(2).asText());
        String url = payload.path(1).asText("");
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new IllegalStateException("원문 주소가 없습니다.");
        return url;
    }

    private String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = google.send(request, BoundedHttpBody.utf8(3_000_000));
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body();
    }

    /** 언론사 페이지를 읽는다. 리다이렉트는 직접 따라가며 매번 내부망 주소가 아닌지 확인한다. */
    private String fetchPage(String url) throws Exception {
        URI target = URI.create(url);
        for (int hop = 0; hop < 4; hop++) {
            requirePublicHost(target);
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.6")
                    .GET().build(), BoundedHttpBody.bytes(3_000_000));
            int status = response.statusCode();
            if (status / 100 == 3) {
                String location = response.headers().firstValue("Location").orElseThrow();
                target = target.resolve(location);
                continue;
            }
            if (status / 100 != 2) throw new IllegalStateException("HTTP " + status);
            byte[] bytes = response.body();
            return new String(bytes, charsetOf(response.headers().firstValue("Content-Type").orElse(""), bytes));
        }
        throw new IllegalStateException("리다이렉트가 너무 많습니다.");
    }

    private static void requirePublicHost(URI uri) throws Exception {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) || uri.getHost() == null || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) {
            throw new IllegalStateException("지원하지 않는 주소입니다.");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (!PublicNetworkAddress.allowed(address)) {
                throw new IllegalStateException("내부망 주소는 읽지 않습니다.");
            }
        }
    }

    private static Charset charsetOf(String contentType, byte[] bytes) {
        Matcher header = Pattern.compile("charset=([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
        String name = header.find() ? header.group(1) : null;
        if (name == null) {
            Matcher meta = META_CHARSET.matcher(new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1));
            if (meta.find()) name = meta.group(1);
        }
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (Exception unsupported) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * 본문 컨테이너를 찾아 첫 두 문단을 꺼낸다. 문단 태그가 없으면 줄바꿈 단위로, 그래도 없으면 요약 메타를 쓴다.
     * 컨테이너가 닫히는 곳까지만 본다. 본문을 &lt;br&gt;이나 &lt;div&gt;로만 나누는 언론사에서 컨테이너 밖의
     * 관련 기사 목록이나 발행인 정보의 &lt;p&gt;를 본문으로 잡지 않기 위해서다.
     */
    static List<String> extractParagraphs(String html) {
        String cleaned = NOISE.matcher(html).replaceAll(" ");
        int start = -1;
        int end = cleaned.length();
        Matcher articleBody = ARTICLE_BODY.matcher(cleaned);
        Matcher container = BODY_CONTAINER.matcher(cleaned);
        Matcher articleTag = ARTICLE_TAG.matcher(cleaned);
        Matcher opening = articleBody.find() ? articleBody : container.find() ? container : articleTag.find() ? articleTag : null;
        if (opening != null) {
            start = opening.end();
            end = elementEnd(cleaned, opening.start(), start);
        }
        String region = start < 0 ? cleaned : cleaned.substring(start, end);

        List<String> paragraphs = new ArrayList<>();
        /* 본문 뒤에 붙는 영상·관련 기사 목록은 보통 목록이나 소제목으로 시작한다. 그 앞부분을 먼저 보고,
           문단이 모자랄 때만 컨테이너 전체를 본다. */
        String flow = region.split("(?i)<(?:/article|section|ul|ol|h[1-6])\\b", 2)[0];
        collectParagraphs(paragraphs, flow, start >= 0);
        if (paragraphs.size() < PARAGRAPHS) collectParagraphs(paragraphs, region, false);
        if (paragraphs.isEmpty()) {
            Matcher description = OG_DESCRIPTION.matcher(html);
            if (description.find()) addIfBody(paragraphs, description.group(1));
        }
        return paragraphs;
    }

    /**
     * 문단 태그를 먼저 보고, 모자라면 문단 태그 없이 &lt;br&gt;나 &lt;div&gt;로 줄을 나누는 CMS처럼 줄 단위로
     * 다시 본다. 줄 단위는 문단 태그도 경계로 삼아 문서 순서를 지키고, 더 많이 찾은 쪽을 쓴다.
     */
    private static void collectParagraphs(List<String> paragraphs, String html, boolean lineBreaks) {
        List<String> tagged = new ArrayList<>(paragraphs);
        Matcher paragraph = PARAGRAPH.matcher(html);
        while (paragraph.find() && tagged.size() < PARAGRAPHS) addIfBody(tagged, paragraph.group(1));
        List<String> best = tagged;
        if (tagged.size() < PARAGRAPHS && lineBreaks) {
            List<String> lines = new ArrayList<>(paragraphs);
            for (String block : LINE_BREAKS.split(html)) {
                if (lines.size() >= PARAGRAPHS) break;
                addIfBody(lines, block);
            }
            if (lines.size() > tagged.size()) best = lines;
        }
        paragraphs.clear();
        paragraphs.addAll(best);
    }

    /**
     * {@code tagStart}에서 열린 요소가 닫히는 위치. 같은 이름의 중첩 요소를 세어 짝을 맞춘다.
     * 짝을 찾지 못하면 예전처럼 20만 자까지만 본다.
     */
    static int elementEnd(String html, int tagStart, int contentStart) {
        Matcher name = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)").matcher(html);
        int fallback = Math.min(html.length(), contentStart + 200_000);
        if (!name.find(tagStart) || name.start() != tagStart) return fallback;
        Matcher tags = Pattern.compile("<(/?)" + name.group(1) + "\\b[^>]*?(/?)>", Pattern.CASE_INSENSITIVE).matcher(html);
        tags.region(contentStart, html.length());
        int depth = 1;
        while (tags.find()) {
            if (!tags.group(1).isEmpty()) {
                if (--depth == 0) return tags.start();
            } else if (tags.group(2).isEmpty()) {
                depth++;
            }
        }
        return fallback;
    }

    private static void addIfBody(List<String> paragraphs, String fragment) {
        String text = unescape(TAG.matcher(fragment).replaceAll(" ")).replaceAll("[\\s\\u00a0]+", " ").trim();
        if (text.length() < MIN_PARAGRAPH || NOT_BODY.matcher(text).find() || paragraphs.contains(text)) return;
        if (text.length() > MAX_PARAGRAPH) text = text.substring(0, MAX_PARAGRAPH).trim() + "…";
        paragraphs.add(text);
    }

    private static String unescape(String text) {
        Matcher entity = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (entity.find()) {
            String code = entity.group(1);
            String value;
            try {
                if (code.startsWith("#x") || code.startsWith("#X")) value = new String(Character.toChars(Integer.parseInt(code.substring(2), 16)));
                else if (code.startsWith("#")) value = new String(Character.toChars(Integer.parseInt(code.substring(1))));
                else value = NAMED_ENTITIES.getOrDefault(code.toLowerCase(), entity.group());
            } catch (IllegalArgumentException invalid) {
                value = entity.group();
            }
            entity.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        entity.appendTail(out);
        return out.toString();
    }
}
