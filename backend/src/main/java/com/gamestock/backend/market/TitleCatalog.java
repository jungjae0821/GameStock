package com.gamestock.backend.market;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.gamestock.backend.market.TitleService.TitleDefinition;

/**
 * The title catalog: every title's id, name, description and category, in display order.
 * {@link TitleService#TITLES} exposes the same list to existing callers.
 */
final class TitleCatalog {
    private TitleCatalog() { }

    static final List<TitleDefinition> TITLES = List.of(
            new TitleDefinition("newbie", "뉴비", "계정으로 처음 로그인", "자산", false),
            new TitleDefinition("ant", "개미", "아무 주식이나 1주 보유", "자산", false),
            new TitleDefinition("asset_30m", "소액 자산가", "총 자산 3천만원 돌파", "자산", false),
            new TitleDefinition("asset_50m", "중산층 자산가", "총 자산 5천만원 돌파", "자산", false),
            new TitleDefinition("asset_100m", "1억원의 사나이", "총 자산 1억원 돌파", "자산", false),
            new TitleDefinition("asset_1b", "10억원의 사나이", "총 자산 10억원 돌파", "자산", false),
            new TitleDefinition("asset_3b", "30억원의 사나이", "총 자산 30억원 돌파", "자산", false),
            new TitleDefinition("asset_5b", "50억원의 사나이", "총 자산 50억원 돌파", "자산", false),
            new TitleDefinition("asset_10b", "100억의 사나이", "총 자산 100억원 돌파", "자산", false),
            new TitleDefinition("asset_100b", "세계1위 부자", "총 자산 1000억원 돌파", "자산", false),
            new TitleDefinition("bottom_10", "흑우", "수익률 하위 10% 달성", "수익률", false),
            new TitleDefinition("bottom_5", "호구", "수익률 하위 5% 달성", "수익률", false),
            new TitleDefinition("loss_99", "주식못함", "수익률 -99% 달성", "수익률", false),
            new TitleDefinition("holder_50", "큰손", "한 종목 50주 이상 보유", "보유", false),
            new TitleDefinition("holder_150", "대주주", "한 종목 150주 이상 보유", "보유", false),
            new TitleDefinition("starter", "스타터", "닉네임 변경", "활동", false),
            new TitleDefinition("attend_7", "일주일 개근", "7일 연속 매일 로그인", "활동", false),
            new TitleDefinition("attend_30", "한달 개근", "30일 연속 매일 로그인", "활동", false),
            new TitleDefinition("attend_180", "개근상", "180일(6달) 연속 매일 로그인", "활동", false),
            new TitleDefinition("trades_10", "투자 준비생", "하루 체결 10회 이상", "거래", false),
            new TitleDefinition("trades_100", "단타충", "하루 체결 100회 이상", "거래", false),
            new TitleDefinition("diversify", "존버의 신", "한 종목을 1주 이상 보유한 채로 다른 종목 1주 이상 매수", "거래", false),
            new TitleDefinition("all_in", "인생은 한방", "단일 종목에 전 재산의 90% 이상 투자", "거래", false),
            new TitleDefinition("collector_5", "칭호 수집가", "칭호 5종류 이상 보유", "수집", false),
            new TitleDefinition("collector_10", "칭호 컬렉터", "칭호 10종류 이상 보유", "수집", false),
            new TitleDefinition("collector_15", "칭호 컬렉팅 마스터", "칭호 15종류 이상 보유", "수집", false),
            new TitleDefinition("collector_all", "칭호 올컬렉터", "다른 모든 칭호 보유 (특별 칭호 포함)", "수집", false),
            new TitleDefinition("special_syuangel", "슈엔젤", "프로모션 코드를 입력하면 획득 가능", "특별", false),
            new TitleDefinition("special_misilis_holder", "미실리스 대주주", "프로모션 코드를 입력하면 획득 가능", "특별", false),
            new TitleDefinition("special_syuaga", "슈아가", "프로모션 코드를 입력하면 획득 가능", "특별", false),
            new TitleDefinition("weekly_3", "주간 수익률 3위", "주간 수익률 3위 이내 · 매주 월요일 지급", "주간", true),
            new TitleDefinition("weekly_2", "주간 수익률 2위", "주간 수익률 2위 이내 · 매주 월요일 지급", "주간", true),
            new TitleDefinition("weekly_1", "주간 수익률 1위", "주간 수익률 1위 · 매주 월요일 지급", "주간", true));
    static final Map<String, TitleDefinition> BY_ID = new HashMap<>();
    static { for (TitleDefinition title : TITLES) BY_ID.put(title.id(), title); }
}
