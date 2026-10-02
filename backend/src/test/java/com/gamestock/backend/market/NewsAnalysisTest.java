package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small, hand-labelled fixtures for the explainable news gate and sentiment
 * score. The fixtures intentionally cover false positives and game incidents,
 * not only obvious positive headlines.
 */
class NewsAnalysisTest {
    private final NewsFeedService sentiment = new NewsFeedService(null);

    @Test
    void relevanceRequiresGameContextAndKeepsGameIncidents() {
        assertTrue(NewsRelevance.isRelevant("UMA", "우마무스메 대규모 업데이트", "게임 이벤트 안내", ""));
        assertTrue(NewsRelevance.isRelevant("GOV", "니케 서버 장애 논란", "접속 불가와 보상 안내", ""));
        assertTrue(NewsRelevance.isRelevant("BA", "블루 아카이브 인터뷰", "인벤 개발자 인터뷰", "인벤"));

        // A game alias by itself must not pull an unrelated article into the feed.
        assertFalse(NewsRelevance.isRelevant("UMA", "우마무스메 경마 경기 결과", "스포츠 경기 소식", ""));
        assertFalse(NewsRelevance.isRelevant("BA", "블루 아카이브 야구 선수 인터뷰", "프로야구 소식", ""));
    }

    @Test
    void incidentContextIsSharedWithMajorNewsRule() {
        assertTrue(NewsRelevance.hasIncidentContext("니케 개인정보 유출 사건사고"));
        assertTrue(NewsRelevance.hasIncidentContext("블루 아카이브 서버 장애"));
        assertFalse(NewsRelevance.hasIncidentContext("우마무스메 신규 캐릭터 공개"));
    }

    @Test
    void addedListingsRequireTheirCurrentGameAndRejectPreviousNames() {
        List<String> codes = List.of("GI", "SR", "AK", "WH", "PX", "LT", "ES", "MH", "EL", "PW", "SD");
        List<String> names = List.of("원신", "붕괴: 스타레일", "명일방주: 엔드필드", "명조: 워더링 웨이브",
                "쿠키런: 킹덤", "발로란트", "에픽세븐", "카트라이더 러쉬플러스", "오버워치", "팰월드", "트릭컬 리바이브");
        for (int index = 0; index < codes.size(); index++) {
            String code = codes.get(index);
            assertTrue(NewsRelevance.isRelevant(code, names.get(index) + " 신규 업데이트"), code);
            assertTrue(NewsRelevance.isRelevant(code, names.get(index) + " 서버 장애"), code);
            assertFalse(NewsRelevance.isRelevant(code, "다른 게임 신규 업데이트"), code);
        }
        assertFalse(NewsRelevance.isRelevant("AK", "명일방주 신규 업데이트"));
        assertFalse(NewsRelevance.isRelevant("PX", "페르소나5: 더 팬텀 X 신규 업데이트"));
        assertFalse(NewsRelevance.isRelevant("LT", "림버스 컴퍼니 신규 업데이트"));
        assertFalse(NewsRelevance.isRelevant("MH", "몬스터헌터 와일즈 신규 업데이트"));
        assertFalse(NewsRelevance.isRelevant("EL", "엘든 링 신규 업데이트"));
        assertFalse(NewsRelevance.isRelevant("SD", "스타듀 밸리 신규 업데이트"));
        assertFalse(NewsRelevance.isRelevant("LT", "발로란트 야구 선수 인터뷰"));
        assertFalse(NewsRelevance.isRelevant("PX", "PC 확대 비율을 바꿀 때 확인할 쿠키런 킹덤 빙고게임 화면 - Calgary Roughnecks"));
        assertFalse(NewsRelevance.isRelevant("PX", "특수 라운드에만 나오는 쿠키런 킹덤 빙고게임 심벌 정보 - Histoire pour Tous"));
    }

    @Test
    void handLabelledSentimentSamplesHaveExpectedDirection() {
        List<Sample> samples = List.of(
                new Sample("우마무스메 대규모 업데이트 흥행", "신규 콘텐츠가 좋은 반응을 얻었다", 1),
                new Sample("블루 아카이브 서버 장애 논란", "긴급 점검이 진행됐다", -1),
                new Sample("니케 개인정보 유출 사건사고", "이용자 피해가 확인됐다", -1),
                new Sample("우마무스메 신규 소식", "팬 커뮤니티 소식", 0),
                // The longer negative phrase is removed before positive terms,
                // so "출시" is not incorrectly counted as a positive signal.
                new Sample("블루 아카이브 출시 취소", "출시 계획이 철회됐다", -1));

        long correct = samples.stream()
                .filter(sample -> direction(sentiment.newsImpact(sample.title(), sample.description())) == sample.expectedDirection())
                .count();
        assertEquals(samples.size(), correct, "hand-labelled sentiment fixture accuracy");
    }

    @Test
    void sentimentScoreIsBoundedAndTitleSignalsHaveMoreWeight() {
        double titleScore = sentiment.newsImpact("우마무스메 흥행", "");
        double bodyScore = sentiment.newsImpact("우마무스메", "흥행");
        double extreme = sentiment.newsImpact("우마무스메 대규모 업데이트 흥행 성장 성공 수상", "");

        assertTrue(titleScore > bodyScore);
        assertTrue(extreme <= 10.0 && extreme >= -10.0);
    }

    @Test
    void commonPositiveHeadlinePhrasesProducePositiveSignals() {
        List<String> positiveHeadlines = List.of(
                "니케 흥행 돌풍, 글로벌 이용자 급증",
                "블루 아카이브 신규 업데이트 좋은 반응",
                "우마무스메 성공적 출시와 호평 이어져",
                "게임 매출 신기록 달성",
                "니케 이벤트 개막, 신규 캐릭터 등장",
                "블루 아카이브 신규 보스 공개",
                "블루 아카이브 신규 보스·캐릭터 2종 동시 공개",
                "니케 신규 이벤트 개막",
                "우마무스메 신규 캐릭터 등장",
                "니케 서비스 개선 및 성능 최적화",
                "블루 아카이브 편의성 개선 업데이트",
                "우마무스메 버그 수정과 운영 안정화"
        );

        positiveHeadlines.forEach(title ->
                assertTrue(sentiment.newsImpact(title, "") > 0,
                        () -> "positive headline was scored non-positive: " + title));
    }

    @Test
    void improvementRequestIsNotMistakenForPositiveOutcome() {
        assertTrue(sentiment.newsImpact("블루 아카이브 서비스 개선 필요", "이용자 불편이 계속되고 있다") < 0);
        assertTrue(sentiment.newsImpact("니케 서비스 개선이 필요하다", "이용자 불만이 이어지고 있다") < 0);
        assertTrue(sentiment.newsImpact("니케 서비스 개선 업데이트", "문제 해결과 최적화가 적용됐다") > 0);
    }

    @Test
    void cancellationContextOverridesPositiveContentWords() {
        assertTrue(sentiment.newsImpact("니케 이벤트 개막 취소", "") < 0);
        assertTrue(sentiment.newsImpact("블루 아카이브 신규 보스 공개 취소", "") < 0);
    }

    private static int direction(double score) {
        return score > 0 ? 1 : score < 0 ? -1 : 0;
    }

    private record Sample(String title, String description, int expectedDirection) { }
}
