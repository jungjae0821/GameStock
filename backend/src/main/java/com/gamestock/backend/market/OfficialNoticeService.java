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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 게임사 공식 채널의 글을 "공식 발표"로 수집한다. 지금 받는 곳은 세 군데다.
 * <ul>
 *   <li>니케: 한국 공식 사이트(nikke-kr.com/news.html)의 공지사항 탭. 사이트가 Level Infinite
 *       커뮤니티 서버의 공개 JSON API로 목록을 받으므로 그 API를 그대로 호출한다.</li>
 *   <li>우마무스메 프리티 더비: 다음 공식 카페(cafe.daum.net/umamusume-kor)의 공지사항·업데이트
 *       게시판. 모바일 게시판 페이지가 글 목록을 스크립트 변수로 내려 주므로 그것을 읽고,
 *       본문은 글 페이지에서 뽑는다.</li>
 *   <li>블루 아카이브: 넥슨 공식 포럼(forum.nexon.com/bluearchive)의 공지사항·업데이트 게시판.
 *       포럼 화면이 쓰는 공개 JSON API로 목록과 글 본문을 받는다.</li>
 * </ul>
 *
 * 공식 글은 언론 기사와 달리 게임 이름이 제목에 없어도 해당 게임의 글이므로 관련성 필터를
 * 거치지 않고, 주가에는 {@link NewsValuation}이 {@link #WEIGHT}배로 반영한다. 출처별로 처음
 * 수집할 때 이미 올라와 있던 글은 목록에만 넣고 주가에는 반영하지 않는다.
 */
@Service
@ConfigurationProperties(prefix = "gamestock.news.official")
public class OfficialNoticeService {
    private static final Logger log = LoggerFactory.getLogger(OfficialNoticeService.class);

    static final String SOURCE = "공식 발표";
    /** 공식 발표가 같은 크기의 언론 기사보다 주가를 얼마나 더 움직이는지. */
    static final double WEIGHT = 1.5;

    static final String NIKKE_CODE = "GOV";
    static final String NIKKE_DETAIL = "https://nikke-kr.com/newsdetail.html?content_id=";
    private static final String NIKKE_API =
            "https://na-community.playerinfinite.com/api/gpts.information_feeds_svr.InformationFeedsSvr/GetContentByLabel";
    /* 사이트의 NEWS 칼럼(309) 아래 "공지사항" 탭(892). 사이트 개편으로 바뀌면 목록이 비어 돌아온다. */
    private static final String NIKKE_REQUEST =
            "{\"language\":[\"ko\"],\"gameid\":\"16\",\"offset\":0,\"get_num\":10,\"ext_info_type_list\":[0,1,2],"
                    + "\"secondary_label_id\":892,\"primary_label_id\":309}";

    static final String UMA_CODE = "UMA";
    static final String UMA_CAFE = "https://cafe.daum.net/umamusume-kor/";
    private static final String UMA_MOBILE = "https://m.cafe.daum.net/umamusume-kor/";
    /** 공지사항(Z4os)과 업데이트(ZBhu) 게시판. */
    static final List<String> UMA_BOARDS = List.of("Z4os", "ZBhu");
    /* 게시판마다 최신 몇 개까지 본문을 읽을지. 1시간에 한 번이라 카페에 주는 부담은 작다. */
    private static final int UMA_PER_BOARD = 10;
    private static final String BROWSER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36";

    static final String BA_CODE = "BA";
    static final String BA_FORUM = "https://forum.nexon.com/bluearchive/";
    private static final String BA_API = "https://forum.nexon.com/api/v1/";
    /** 공지사항(1018)과 업데이트(1076) 게시판. */
    static final List<String> BA_BOARDS = List.of("1018", "1076");
    private static final int BA_PER_BOARD = 10;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Pattern CONTENT_ID = Pattern.compile("[A-Za-z0-9]{8,64}");
    private static final Pattern BOARD_ID = Pattern.compile("[A-Za-z0-9]{2,10}");
    private static final Pattern THREAD_ID = Pattern.compile("\\d{1,12}");
    private static final Pattern CAFE_ITEM = Pattern.compile("articles\\.push\\(\\{(.*?)\\}\\);", Pattern.DOTALL);
    private static final Pattern CAFE_DATAID = Pattern.compile("dataid:\\s*(\\d+)");
    private static final Pattern CAFE_FLDID = Pattern.compile("fldid:\\s*\"([A-Za-z0-9]+)\"");
    private static final Pattern CAFE_TITLE = Pattern.compile("title:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CAFE_TIME = Pattern.compile("articleElapsedTime:\\s*\"([^\"]*)\"");
    private static final Pattern AGO = Pattern.compile("^(?:(\\d+)\\s*시간)?\\s*(?:(\\d+)\\s*분)?\\s*전$");
    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final DateTimeFormatter CAFE_DATE = DateTimeFormatter.ofPattern("yy.MM.dd");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)<br\\s*/?>|</p\\s*>|</div\\s*>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    /* 블루 아카이브 공지는 "📣 선생님께 …"처럼 이모지로 시작하므로 앞의 기호는 건너뛴다. */
    private static final Pattern GREETING = Pattern.compile("^[^\\p{L}\\p{N}]*(?:안녕하세요|지휘관|트레이너|선생님)");
    private static final ObjectMapper JSON = new ObjectMapper();

    record Notice(String id, String title, String body, Instant publishedAt, String link) {
        Notice withBody(String body) { return new Notice(id, title, body, publishedAt, link); }
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
    @Scheduled(fixedDelayString = "${gamestock.news.official.refresh-ms:3600000}",
            initialDelayString = "${gamestock.news.official.refresh-ms:3600000}")
    public void fetchScheduled() {
        fetch();
    }

    private void fetch() {
        if (!enabled) return;
        // 비공개 API나 카페 화면은 예고 없이 바뀔 수 있다. 한쪽이 깨져도 다른 쪽과
        // 언론 기사 수집에는 영향이 없도록 출처마다 따로 끝낸다.
        try {
            List<Notice> notices = parseNikke(post(NIKKE_API, NIKKE_REQUEST));
            log.info("니케 공식 발표 수집 완료: {}개 중 새 글 {}개", notices.size(), save(NIKKE_CODE, NIKKE_DETAIL, notices));
        } catch (Exception error) {
            log.warn("니케 공식 발표 수집 실패", error);
        }
        try {
            List<Notice> notices = fetchUmamusume();
            log.info("우마무스메 공식 발표 수집 완료: {}개 중 새 글 {}개", notices.size(), save(UMA_CODE, UMA_CAFE, notices));
        } catch (Exception error) {
            log.warn("우마무스메 공식 발표 수집 실패", error);
        }
        try {
            List<Notice> notices = fetchBlueArchive();
            log.info("블루 아카이브 공식 발표 수집 완료: {}개 중 새 글 {}개", notices.size(), save(BA_CODE, BA_FORUM, notices));
        } catch (Exception error) {
            log.warn("블루 아카이브 공식 발표 수집 실패", error);
        }
        events.publishEvent(new NewsChangedEvent());
    }

    private List<Notice> fetchUmamusume() throws Exception {
        List<Notice> notices = new ArrayList<>();
        Instant now = Instant.now();
        for (String board : UMA_BOARDS) {
            List<Notice> listed = parseCafeBoard(get(UMA_MOBILE + board), board, now);
            for (Notice notice : listed.subList(0, Math.min(UMA_PER_BOARD, listed.size()))) {
                String body = "";
                try {
                    body = cafeArticleBody(get(UMA_MOBILE + board + "/" + notice.id().substring(board.length() + 1)));
                } catch (Exception error) {
                    log.debug("카페 글 본문을 읽지 못했습니다: {}", notice.link(), error);
                }
                notices.add(notice.withBody(body));
                Thread.sleep(500);
            }
        }
        return notices;
    }

    private List<Notice> fetchBlueArchive() throws Exception {
        List<Notice> notices = new ArrayList<>();
        Instant now = Instant.now();
        for (String board : BA_BOARDS) {
            List<Notice> listed = parseForumBoard(get(BA_API + "board/" + board
                    + "/threads?alias=bluearchive&paginationType=PAGING&pageSize=" + BA_PER_BOARD
                    + "&pageNo=1&blockSize=5&hideType=WEB"), board, now);
            for (Notice notice : listed) {
                String body = "";
                try {
                    String thread = notice.id().substring(board.length() + 1);
                    body = forumThreadBody(get(BA_API + "thread/" + thread + "?alias=bluearchive"));
                } catch (Exception error) {
                    log.debug("포럼 글 본문을 읽지 못했습니다: {}", notice.link(), error);
                }
                notices.add(notice.withBody(body));
                Thread.sleep(500);
            }
        }
        return notices;
    }

    private String post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json;charset=utf-8")
                .header("User-Agent", "GameStock/1.0 news reader")
                .header("X-AreaId", "na")
                .header("X-GameId", "16")
                .header("X-Language", "ko")
                .header("X-Source", "pc_web")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", BROWSER_AGENT)
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .GET()
                .build();
        return send(request);
    }

    private String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, BoundedHttpBody.utf8(2_000_000));
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + request.uri());
        return response.body();
    }

    static List<Notice> parseNikke(String response) throws Exception {
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
                    seconds > 0 ? Instant.ofEpochSecond(seconds) : null, NIKKE_DETAIL + contentId));
        }
        return notices;
    }

    /**
     * 다음 카페 모바일 게시판 목록. 페이지 안의 {@code articles.push({...})} 스크립트에서
     * 글 번호, 제목, 게시 시각을 읽는다. 위에 고정된 공지는 이 목록에 들어 있지 않다.
     */
    static List<Notice> parseCafeBoard(String html, String board, Instant now) {
        // 로그인 안내나 점검 화면처럼 목록 변수가 없는 페이지는 "글 없음"이 아니라 실패로 남긴다.
        if (!html.contains("var articles"))
            throw new IllegalStateException("카페 게시판 형식을 알아볼 수 없습니다: " + board);
        List<Notice> notices = new ArrayList<>();
        Matcher item = CAFE_ITEM.matcher(html);
        while (item.find()) {
            String block = item.group(1);
            String dataId = group(CAFE_DATAID, block);
            String fldId = group(CAFE_FLDID, block);
            String title = plain(unescapeScript(group(CAFE_TITLE, block))).trim();
            if (dataId.isEmpty() || !board.equals(fldId) || !BOARD_ID.matcher(fldId).matches() || title.isEmpty()) continue;
            notices.add(new Notice(fldId + "-" + dataId, cut(title, 150), "",
                    cafeTime(group(CAFE_TIME, block), now), UMA_CAFE + fldId + "/" + dataId));
        }
        return notices;
    }

    /** 목록의 게시 시각. 오늘 글은 "2시간 53분 전"·"14:20", 그 전 글은 "26.11.03"처럼 날짜만 나온다. */
    static Instant cafeTime(String value, Instant now) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) return null;
        if (text.startsWith("방금")) return now;
        Matcher ago = AGO.matcher(text);
        if (ago.matches() && (ago.group(1) != null || ago.group(2) != null)) {
            long hours = ago.group(1) == null ? 0 : Long.parseLong(ago.group(1));
            long minutes = ago.group(2) == null ? 0 : Long.parseLong(ago.group(2));
            return now.minus(Duration.ofHours(hours).plusMinutes(minutes));
        }
        Matcher clock = CLOCK.matcher(text);
        if (clock.matches()) {
            LocalTime time = LocalTime.of(Integer.parseInt(clock.group(1)), Integer.parseInt(clock.group(2)));
            return LocalDate.ofInstant(now, KST).atTime(time).atZone(KST).toInstant();
        }
        try {
            return LocalDate.parse(text, CAFE_DATE).atStartOfDay(KST).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** 카페 글 페이지에서 본문 글자만 뽑는다. 이미지만 있는 글이면 빈 문자열이다. */
    static String cafeArticleBody(String html) {
        int start = html.indexOf("id=\"article\"");
        if (start < 0) return "";
        start = html.indexOf('>', start) + 1;
        int end = html.indexOf("<script", start);
        return htmlText(html.substring(start, end < 0 ? html.length() : end));
    }

    /** 본문 HTML을 줄 단위 글자로 바꾼다. 문단과 줄바꿈 태그를 줄로 보고 빈 줄은 버린다. */
    private static String htmlText(String region) {
        StringBuilder text = new StringBuilder();
        for (String line : plain(BLOCK_END.matcher(region).replaceAll("\n")).split("\\n")) {
            String clean = line.replaceAll("\\s+", " ").strip();
            if (!clean.isEmpty()) text.append(clean).append('\n');
        }
        return cut(text.toString().strip(), 3_000);
    }

    /**
     * 넥슨 포럼 게시판 목록 API 응답. 위에 고정된 글도 이 목록에 함께 들어 있다.
     * 지워졌거나 웹에서 숨긴 글, 아직 게시 시각이 오지 않은 글은 뺀다.
     */
    static List<Notice> parseForumBoard(String response, String board, Instant now) throws Exception {
        JsonNode root = JSON.readTree(response);
        // 점검 화면이나 오류 응답처럼 목록 필드가 없는 응답은 "글 없음"이 아니라 실패로 남긴다.
        if (!root.path("threads").isArray())
            throw new IllegalStateException("포럼 게시판 형식을 알아볼 수 없습니다: " + board);
        List<Notice> notices = new ArrayList<>();
        for (JsonNode item : root.path("threads")) {
            String threadId = item.path("threadId").asText("");
            String title = plain(item.path("title").asText("")).trim();
            if (!THREAD_ID.matcher(threadId).matches() || !board.equals(item.path("boardId").asText(""))
                    || title.isEmpty() || item.path("isDelete").asBoolean(false)
                    || item.path("isWebHide").asBoolean(false) || !"ON".equals(item.path("release").asText("ON")))
                continue;
            if (item.path("displayStartDate").asLong(0) > now.getEpochSecond()) continue;
            long created = item.path("createDate").asLong(0);
            notices.add(new Notice(board + "-" + threadId, cut(title, 150), "",
                    created > 0 ? Instant.ofEpochSecond(created) : null,
                    BA_FORUM + "board_view?board=" + board + "&thread=" + threadId));
        }
        return notices;
    }

    /** 포럼 글 API 응답에서 본문 글자만 뽑는다. 본문이 비면 목록 요약을 쓴다. */
    static String forumThreadBody(String response) throws Exception {
        JsonNode root = JSON.readTree(response);
        String text = htmlText(root.path("content").asText(""));
        return text.isEmpty() ? cut(plain(root.path("summary").asText("")).strip(), 3_000) : text;
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
     * 원문 주소로 기존 행을 찾아 고친다. 공지는 게시 뒤에도 제목이 바뀌므로("…(10/29 내용 업데이트)")
     * 제목이 아니라 원문 주소로 같은 글을 알아본다. linkPrefix는 출처의 원문 주소 앞부분으로,
     * 그 출처의 글을 처음 받는지 판단하는 데 쓴다. 반환값은 새로 넣은 글 수.
     */
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
                    "paragraphs", paragraphs(notice.body()), "url", notice.link()));
        } catch (Exception error) {
            return null;
        }
    }

    /** 원문 주소로 공식 발표인지 판단한다. 가치 계산 표에는 출처 열이 없어 설명의 주소로 알아본다. */
    static boolean isOfficialLink(String link) {
        return link != null && (link.startsWith(NIKKE_DETAIL) || link.startsWith(UMA_CAFE) || link.startsWith(BA_FORUM));
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String unescapeScript(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 == value.length()) { out.append(c); continue; }
            char next = value.charAt(++i);
            if (next == 'u' && i + 4 < value.length()) {
                try {
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                    continue;
                } catch (NumberFormatException ignored) { }
            }
            out.append(switch (next) { case 'n' -> ' '; case 't' -> ' '; default -> next; });
        }
        return out.toString();
    }

    private static String plain(String value) {
        return TAG.matcher(value == null ? "" : value).replaceAll(" ")
                .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
