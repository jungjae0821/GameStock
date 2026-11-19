package com.gamestock.backend.market;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

import static com.gamestock.backend.market.PersonaDecisions.Quote;

/**
 * 운영 봇(AI 트레이더)이 종목토론에 남기는 짧은 한마디. 하루 등락이 큰 종목에만 반응하고, 자기 주력 게임을 먼저 본다.
 * 화면은 운영 봇 닉네임에 「AI」 표시를 붙이므로 사람인 척하지 않는다. 사라고 권하는 말은 쓰지 않고,
 * 자기 성격(장기·단타·초보)대로 지금 움직임에 대한 감상만 남긴다.
 */
final class PersonaTalk {
    /** 이만큼(하루 등락률 절댓값) 넘게 움직인 종목에만 글을 쓴다. */
    static final double MOVE_THRESHOLD = 0.03;
    /** 한 봇이 하루에 쓰는 글 수 상한. */
    static final int DAILY_POSTS = 3;
    /** 접속해 있는 1분마다 글을 쓸지 고르는 확률. 한두 시간 접속하면 한 번쯤 쓴다. */
    static final double CHANCE_PER_MINUTE = 0.02;
    /** 같은 종목에 운영 봇 글이 이 시간(분) 안에 있었으면 쓰지 않는다. 토론이 봇 글로 덮이지 않게. */
    static final int STOCK_COOLDOWN_MINUTES = 180;

    private PersonaTalk() { }

    record Line(String code, String body) { }

    /** 글을 쓸 종목. 기준을 넘게 움직인 종목 가운데 주력 게임이 먼저이고, 그다음 많이 움직인 순이다. */
    static Optional<Quote> pick(Persona persona, List<Quote> quotes) {
        return quotes.stream()
                .filter(q -> q.price() > 0 && q.prevClose() > 0 && Math.abs(q.dayChange()) >= MOVE_THRESHOLD)
                .min(Comparator.comparing((Quote q) -> !q.code().equals(persona.fandomCode()))
                        .thenComparing(q -> -Math.abs(q.dayChange())));
    }

    static Line line(Persona persona, Quote quote, Random random) {
        boolean up = quote.dayChange() > 0;
        boolean fandom = quote.code().equals(persona.fandomCode());
        String pct = String.format(Locale.ROOT, "%s%.1f%%", up ? "+" : "", quote.dayChange() * 100);
        List<String> lines = switch (persona.style()) {
            case LONG -> up
                    ? List.of("오늘 " + pct + "네요. 저는 원래 계획대로 그냥 들고 갑니다.",
                              pct + " 반갑긴 한데 하루 움직임으로 판단은 안 하려고요.",
                              fandom ? "주력 게임이 " + pct + " 올랐네요. 길게 보는 쪽이라 덤덤하게 봅니다." : "생각보다 빨리 올랐네요(" + pct + "). 급하게 따라가진 않으려고요.")
                    : List.of("오늘 " + pct + "… 흔들리지만 일주일 단위로 보려고요.",
                              pct + "면 아프긴 하네요. 그래도 처음 산 이유가 바뀐 건 아니라서 지켜봅니다.",
                              fandom ? "주력 게임 " + pct + "라니. 업데이트 소식 나올 때까지 기다려 봅니다." : "많이 빠졌네요(" + pct + "). 저는 오늘은 구경만 합니다.");
            case SCALP -> up
                    ? List.of(pct + " 거래량 붙는 거 보니 오늘 재밌겠네요.",
                              "호가 얇아서 휙휙 움직이네요. 오늘 " + pct + ".",
                              pct + " 찍고 눌림 오는지 보는 중. 짧게만 봅니다.")
                    : List.of(pct + " 하락 중이라 호가가 빠르게 바뀌네요.",
                              "오늘 " + pct + ". 반등 나오는지 짧게 지켜봅니다.",
                              pct + "면 체결 소식 계속 뜨겠네요. 저는 손절선 지키는 중.");
            case NEWBIE -> up
                    ? List.of("오늘 " + pct + "?? 이거 무슨 일 있어요?",
                              fandom ? "주력 게임 " + pct + " 실화인가요ㅋㅋ 기분 좋네요" : pct + " 오르는 거 보고 들어왔어요. 다들 뉴스 보셨나요?",
                              "와 " + pct + "… 아직 뭐가 뭔지 모르겠지만 일단 신기하네요")
                    : List.of("오늘 " + pct + "… 이럴 땐 어떻게 해야 하나요ㅠ",
                              fandom ? "주력 게임 " + pct + "라니ㅠ 그래도 팬이라 못 팔겠어요" : pct + " 빠졌네요. 뉴스 탭부터 보러 갑니다",
                              "파란불 " + pct + "… 수습 때 배운 손실한도 생각나네요");
        };
        return new Line(quote.code(), lines.get(random.nextInt(lines.size())));
    }
}
