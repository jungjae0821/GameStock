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
        List<OfficialNoticeService.Notice> notices = OfficialNoticeService.parse(RESPONSE);
        assertEquals(1, notices.size());
        var notice = notices.get(0);
        assertEquals("10월 29일 업데이트 공지", notice.title());
        assertEquals(Instant.ofEpochSecond(1793833593), notice.publishedAt());
        assertEquals("https://nikke-kr.com/newsdetail.html?content_id=a1b2c3d4e5f6", notice.link());
        assertFalse(notice.body().contains("<b>"));
    }

    @Test void apiErrorIsReportedInsteadOfLookingLikeAnEmptyBoard() {
        assertThrows(IllegalStateException.class,
                () -> OfficialNoticeService.parse("{\"code\":1001,\"msg\":\"bad\"}"));
    }

    @Test void previewSkipsGreetingAndShortLines() throws Exception {
        var body = OfficialNoticeService.parse(RESPONSE).get(0).body();
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
    }
}
