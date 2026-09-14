package com.gamestock.backend.market;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight relevance gate for RSS items. Google News searches are broad,
 * so a game-name mention alone is not enough to classify an item as game news.
 */
final class NewsRelevance {
    private static final List<String> COMMON_GAME_CONTEXT = List.of(
            "게임", "game", "gaming", "모바일", "pc", "스팀", "steam", "콘솔", "업데이트",
            "패치", "출시", "신작", "신규", "이벤트", "캐릭터", "스토리", "유저", "플레이",
            "플레이어", "서버", "콘텐츠", "공략", "리뷰", "콜라보", "사전예약", "트레일러",
            "게임사", "개발사", "버전", "가챠");
    /**
     * 사건·사고는 일반적인 게임 문맥 단어가 없어도 게임 가치와 이용자
     * 반응을 크게 바꿀 수 있으므로 별도 신호로 인정한다. 게임 별칭과
     * 함께 나타날 때만 통과시켜, 다른 분야의 사건 기사는 계속 제외한다.
     */
    private static final List<String> INCIDENT_CONTEXT = List.of(
            "해킹", "hack", "hacking", "개인정보 유출", "데이터 유출", "data breach", "보안",
            "불법 프로그램", "핵 사용", "핵 이용", "치팅", "cheat", "exploit", "부정행위",
            "계정 정지", "이용 제한", "제재", "비정상 플레이", "접속 장애", "접속 불가",
            "서버 장애", "서버 다운", "outage", "긴급 점검", "서비스 종료", "서비스 중단",
            "shutdown", "환불", "refund", "과금", "버그", "bug", "오류", "장애", "먹통",
            "논란", "controversy", "비판", "소송", "lawsuit", "법적 분쟁", "사건사고", "사고",
            "피해", "문제");
    private static final List<String> TRUSTED_GAME_SOURCES = List.of(
            "인벤", "게임메카", "루리웹", "디스이즈게임", "게임동아", "아이뉴스24", "전자신문",
            "ign", "gamespot", "pc gamer", "polygon", "gematsu", "4gamer", "famitsu",
            "steam");
    private static final List<String> OFF_TOPIC_CONTEXT = List.of(
            "부동산", "정치", "선거", "국회", "외교", "야구", "축구", "농구", "배구", "골프",
            "테니스", "경마", "주식", "증시", "주가", "채권", "금리", "코인", "가상자산",
            "보드게임", "보드 게임", "calgary", "roughnecks", "nhl", "nfl", "nba", "mlb");

    private static final Map<String, GameProfile> GAME_PROFILES = Map.of(
            "UMA", new GameProfile(
                    List.of("우마무스메 프리티 더비", "우마무스메", "말딸", "umamusume", "pretty derby"),
                    List.of("육성", "트레이너", "서포트 카드", "경주", "경마", "위닝 라이브", "사이게임즈", "4주년", "챔피언스 미팅")),
            "BA", new GameProfile(
                    List.of("블루 아카이브", "블루아카이브", "blue archive"),
                    List.of("학생", "학원", "총력전", "스토리", "선생님", "넥슨게임즈", "대결전", "페스")),
            "GOV", new GameProfile(
                    List.of("승리의 여신 니케", "승리의 여신: 니케", "니케", "goddess of victory nikke", "nikke"),
                    List.of("지휘관", "방주", "스쿼드", "니케 캐릭터", "시프트업", "드레이크", "레이드", "컬래버", "4주년", "제재", "부정행위", "보안", "이용 제한")));

    private NewsRelevance() { }

    static boolean isRelevant(String stockCode, String... parts) {
        GameProfile profile = GAME_PROFILES.get(stockCode == null ? "" : stockCode.toUpperCase(Locale.ROOT));
        if (profile == null) return true;

        String text = normalize(parts);
        if (!containsAny(text, profile.aliases())) return false;

        int commonSignals = countTerms(text, COMMON_GAME_CONTEXT);
        int specificSignals = countTerms(text, profile.contextKeywords());
        int incidentSignals = countTerms(text, INCIDENT_CONTEXT);
        int offTopicSignals = countTerms(text, OFF_TOPIC_CONTEXT);
        boolean trustedSource = containsAny(text, TRUSTED_GAME_SOURCES);

        // A trusted game outlet can use a short headline, but it cannot
        // rescue an item containing an explicit non-game context such as a
        // board-game article that merely shares a short game alias.
        if (trustedSource && offTopicSignals == 0) return true;
        // A game incident can omit words such as "게임" or "업데이트" in a
        // headline. The stock alias plus an incident signal is enough unless
        // the same text is clearly dominated by an unrelated topic.
        if (incidentSignals > 0 && offTopicSignals == 0) return true;
        if (offTopicSignals == 0) return commonSignals > 0 || specificSignals > 0;
        // Terms such as "경마" can be valid for one game but require both a
        // general game signal and a title-specific signal to avoid false hits.
        return commonSignals > 0 && specificSignals > 0;
    }

    /** Reuses the same incident vocabulary for major-news price shocks. */
    static boolean hasIncidentContext(String... parts) {
        return containsAny(normalize(parts), INCIDENT_CONTEXT);
    }

    private static String normalize(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) joined.append(' ').append(part);
        }
        return joined.toString()
                .replaceAll("<[^>]*>", " ")
                .replaceAll("(?i)&nbsp;", " ")
                .replaceAll("(?i)출처:\\s*https?://\\S+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, List<String> terms) {
        return terms.stream().map(term -> term.toLowerCase(Locale.ROOT)).anyMatch(text::contains);
    }

    private static int countTerms(String text, List<String> terms) {
        return (int) terms.stream()
                .map(term -> term.toLowerCase(Locale.ROOT))
                .filter(text::contains)
                .count();
    }

    private record GameProfile(List<String> aliases, List<String> contextKeywords) { }
}
