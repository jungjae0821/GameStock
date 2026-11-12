package com.gamestock.backend.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link NewsExcerptService}에서 분리한 본문 추출 규칙. 언론사 HTML에서 본문 컨테이너를 찾아
 * 첫 두 문단을 꺼내고, 태그와 HTML 엔티티를 걷어 낸다. 상태가 없는 정적 도우미다.
 * 규칙을 바꾸면 {@link NewsExcerptService#EXCERPT_VERSION}을 올린다.
 */
final class NewsTextExtractor {
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

    private static final int PARAGRAPHS = 2;
    private static final int MIN_PARAGRAPH = 30;
    private static final int MAX_PARAGRAPH = 700;

    private NewsTextExtractor() { }

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
