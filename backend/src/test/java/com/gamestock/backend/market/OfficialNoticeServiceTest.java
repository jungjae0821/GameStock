package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfficialNoticeServiceTest {
    /* 니케 공지사항 탭 API 응답의 모양. 필요한 필드만 남겼다. */
    private static final String RESPONSE = """
            {"code":0,"msg":"succ","data":{"info_content":[
              {"content_id":"a1b2c3d4e5f6","title":"10월 29일 업데이트 공지",
               "content_part":"안녕하세요. 승리의 여신: 니케 운영팀입니다.\\n<b>신규 캐릭터</b>와 이벤트 스토리가 추가됩니다.\\n짧음\\n편의성 개선 항목을 아래에서 확인해 주세요.\\n세 번째 문단은 쓰지 않습니다 아주 길게",
               "pub_timestamp":"1793833593"},
              {"content_id":"../../evil","title":"주소 조작","content_part":"x","pub_timestamp":"1"},
              {"content_id":"0f0f0f0f0f","title":"","content_part":"제목 없음","pub_timestamp":"1"}
            ]}}
            """;

    @Test void parsesNoticesAndSkipsUnsafeOrEmptyItems() throws Exception {
        List<OfficialNoticeService.Notice> notices = OfficialNoticeService.parseNikke(RESPONSE);
        assertEquals(1, notices.size());
        var notice = notices.get(0);
        assertEquals("10월 29일 업데이트 공지", notice.title());
        assertEquals(Instant.ofEpochSecond(1793833593), notice.publishedAt());
        assertEquals("https://nikke-kr.com/newsdetail.html?content_id=a1b2c3d4e5f6", notice.link());
        assertFalse(notice.body().contains("<b>"));
    }

    @Test void apiErrorIsReportedInsteadOfLookingLikeAnEmptyBoard() {
        assertThrows(IllegalStateException.class,
                () -> OfficialNoticeService.parseNikke("{\"code\":1001,\"msg\":\"bad\"}"));
    }

    @Test void previewSkipsGreetingAndShortLines() throws Exception {
        var body = OfficialNoticeService.parseNikke(RESPONSE).get(0).body();
        assertEquals(List.of("신규 캐릭터 와 이벤트 스토리가 추가됩니다.","편의성 개선 항목을 아래에서 확인해 주세요."),
                OfficialNoticeService.paragraphs(body));
    }

    @Test void officialNoticeMovesValueMoreThanTheSameMediaHeadline() {
        Instant now = Instant.parse("2026-11-05T00:00:00Z");
        var media = new NewsValuation.Article(1, "니케 업데이트", "출처: https://news.google.com/rss/articles/x", 6, now);
        var official = new NewsValuation.Article(2, "업데이트 공지",
                "출처: https://nikke-kr.com/newsdetail.html?content_id=a1b2c3d4e5f6", 6, now);
        assertFalse(NewsValuation.official(media));
        assertTrue(NewsValuation.official(official));
        assertEquals(NewsValuation.effect(media) * OfficialNoticeService.WEIGHT, NewsValuation.effect(official), 1e-12);
    }

    @Test void lookalikeHostIsNotOfficial() {
        assertFalse(OfficialNoticeService.isOfficialLink("https://nikke-kr.com.evil.test/newsdetail.html?content_id=1"));
        assertFalse(OfficialNoticeService.isOfficialLink(null));
        assertTrue(OfficialNoticeService.isOfficialLink("https://cafe.daum.net/umamusume-kor/ZBhu/227"));
        assertFalse(OfficialNoticeService.isOfficialLink("https://cafe.daum.net/umamusume-kor-fan/ZBhu/227"));
    }

    /* 다음 카페 모바일 게시판 페이지의 목록 스크립트 모양. */
    private static final String CAFE_BOARD = """
            <script>
            var articles = [];
            articles.push({
                dataid: 228,
                fldid: "ZBhu",
                title: "메인 스토리 \\\"제2 부\\\" 공개 예정 안내",
                articleElapsedTime: "2시간 53분 전",
                bbsDepth: "0003gzzzzzzzzzzzzzzzzzzzzzzzzz"
            });
            articles.push({
                dataid: 227,
                fldid: "ZBhu",
                title: "10월 30일(금) 업데이트 안내",
                articleElapsedTime: "26.10.28",
                bbsDepth: "0003hzzzzzzzzzzzzzzzzzzzzzzzzz"
            });
            articles.push({
                dataid: 5,
                fldid: "Other",
                title: "다른 게시판 글",
                articleElapsedTime: "26.10.01"
            });
            </script>
            """;

    @Test void parsesCafeBoardListAndKeepsOnlyThatBoard() {
        Instant now = Instant.parse("2026-11-05T05:00:00Z");
        var notices = OfficialNoticeService.parseCafeBoard(CAFE_BOARD, "ZBhu", now);
        assertEquals(2, notices.size());
        assertEquals("메인 스토리 \"제2 부\" 공개 예정 안내", notices.get(0).title());
        assertEquals("ZBhu-228", notices.get(0).id());
        assertEquals("https://cafe.daum.net/umamusume-kor/ZBhu/228", notices.get(0).link());
        assertEquals(Instant.parse("2026-11-05T02:07:00Z"), notices.get(0).publishedAt());
        // 날짜만 있는 글은 그날 0시(한국 시각)로 둔다.
        assertEquals(Instant.parse("2026-10-27T15:00:00Z"), notices.get(1).publishedAt());
    }

    @Test void cafeTimeReadsTodayFormats() {
        Instant now = Instant.parse("2026-11-05T05:00:00Z");
        assertEquals(now.minusSeconds(180), OfficialNoticeService.cafeTime("3분 전", now));
        assertEquals(now.minusSeconds(3600), OfficialNoticeService.cafeTime("1시간 전", now));
        assertEquals(Instant.parse("2026-11-05T05:20:00Z"), OfficialNoticeService.cafeTime("14:20", now));
        assertEquals(now, OfficialNoticeService.cafeTime("방금 전", now));
        assertNull(OfficialNoticeService.cafeTime("어제쯤", now));
    }

    @Test void pageWithoutArticleListIsAFailureNotAnEmptyBoard() {
        assertThrows(IllegalStateException.class,
                () -> OfficialNoticeService.parseCafeBoard("<html>로그인이 필요합니다</html>", "Z4os", Instant.now()));
    }

    @Test void cafeArticleBodyKeepsTextLinesAndDropsImages() {
        String html = """
                <div class="view_info"><div id="article" class="tx-content-container">
                <p>안녕하세요, 트레이너 여러분.</p><p>11월 6일 정기 점검이 진행됩니다.<br>점검 시간은 약 2시간입니다.</p>
                <img src="https://t1.daumcdn.net/x.png"></div>
                <script>var x = "<p>스크립트 안</p>";</script>
                """;
        assertEquals("안녕하세요, 트레이너 여러분.\n11월 6일 정기 점검이 진행됩니다.\n점검 시간은 약 2시간입니다.",
                OfficialNoticeService.cafeArticleBody(html));
        assertEquals("", OfficialNoticeService.cafeArticleBody("<div id=\"article\"><img src=\"a.png\"></div><script>"));
    }
}
