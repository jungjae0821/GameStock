package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.market.OfficialNoticeService.Notice;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link OfficialNoticeService}에서 분리한 응답 해석 부분. 니케 API, 다음 카페 게시판·글 페이지,
 * 넥슨 포럼 API 응답을 {@link Notice}로 바꾸고 HTML에서 글자만 뽑는다. 네트워크와 DB를 쓰지 않는
 * 순수 함수라 상태 없는 정적 도우미로 둔다. 테스트는 서비스의 같은 이름 메서드로 부른다.
 */
final class OfficialNoticeParser {
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

    private OfficialNoticeParser() {
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
                    seconds > 0 ? Instant.ofEpochSecond(seconds) : null, OfficialNoticeService.NIKKE_DETAIL + contentId));
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
                    cafeTime(group(CAFE_TIME, block), now), OfficialNoticeService.UMA_CAFE + fldId + "/" + dataId));
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
                    OfficialNoticeService.BA_FORUM + "board_view?board=" + board + "&thread=" + threadId));
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

    static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
