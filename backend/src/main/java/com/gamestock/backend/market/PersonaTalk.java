package com.gamestock.backend.market;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.gamestock.backend.market.PersonaDecisions.Quote;

/**
 * 운영 봇(AI 트레이더)이 종목토론에 남기는 짧은 한마디. 하루 등락이 큰 종목에만 반응하고, 자기 주력 게임을 먼저 본다.
 * 화면은 운영 봇 닉네임에 「AI」 표시를 붙이므로 사람인 척하지 않는다. 사라고 권하는 말은 쓰지 않고,
 * 자기 성격(장기·단타·초보)대로 지금 움직임에 대한 감상만 남긴다. 말투는 주식 커뮤니티식 반말·은어로 쓰되,
 * 욕설은 넣지 않는다(종목토론 금칙어 검사도 통과해야 올라간다).
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
        return new Line(quote.code(), body(persona, quote.code(), quote.dayChange(), random));
    }

    /** 커뮤니티식 등락 표기. 0.043 → "+4.3퍼", -0.1 → "-10퍼". */
    static String percent(double change) {
        String number = String.format(Locale.ROOT, "%.1f", Math.abs(change) * 100);
        if (number.endsWith(".0")) number = number.substring(0, number.length() - 2);
        return (change > 0 ? "+" : "-") + number + "퍼";
    }

    /** 예전 말투(존댓말, "+4.3%" 표기)로 남은 운영 봇 글에서 등락률을 읽는 데 쓴다. */
    private static final Pattern OLD_PERCENT = Pattern.compile("([+-]\\d+\\.\\d)%");

    /**
     * 예전 말투로 쓴 운영 봇 글을 지금 말투로 다시 쓴 본문. 예전 표기의 등락률이 없으면(이미 바뀐 글) 비어 있다.
     * 같은 글은 늘 같은 문장으로 바뀌도록 {@code seed}(글 번호)로 고른다.
     */
    static Optional<String> rewrite(Persona persona, String code, String oldBody, long seed) {
        Matcher match = OLD_PERCENT.matcher(oldBody == null ? "" : oldBody);
        if (!match.find()) return Optional.empty();
        double change = Double.parseDouble(match.group(1)) / 100;
        if (change == 0) return Optional.empty();
        return Optional.of(body(persona, code, change, new Random(seed)));
    }

    private static String body(Persona persona, String code, double change, Random random) {
        boolean up = change > 0;
        boolean fandom = code.equals(persona.fandomCode());
        String pct = percent(change);
        List<String> lines = switch (persona.style()) {
            case LONG -> up
                    ? List.of(pct + " ㅋㅋ 존버는 승리한다 이거야",
                              "아 " + pct + " 떴네 근데 난 안 팜. 하루 빨간불로 안 흔들림",
                              fandom ? "내 최애겜 " + pct + " ㅋㅋㅋ 개추 박고 간다 계속 들고감" : pct + " 떡상 ㄷㄷ 근데 이미 늦었다 따라가면 물림",
                              "오늘 " + pct + "인데 호들갑 떨 거 없음. 1년 보고 산 거라",
                              pct + "에 단타충들 몰려오겠네 ㅋㅋ 난 그냥 잔다")
                    : List.of(pct + " 개미털기 오지네 ㅋㅋ 안 털림",
                              "오늘 " + pct + " 아프긴 한데 산 이유 안 바뀜. 존버 간다",
                              fandom ? "최애겜 " + pct + " 실화냐… 업뎃 나올 때까지 버틴다" : pct + " 떡락 ㄷㄷ 오늘은 구경만 함",
                              pct + "에 패닉셀하는 애들 꼭 있더라. 일주일 뒤에 보자",
                              "어제 산 사람 " + pct + " ㅠ 장투는 마음 편하게 묻어두는 거다");
            case SCALP -> up
                    ? List.of(pct + " 거래량 터졌다 ㅋㅋ 오늘 장 재밌네",
                              "호가 얇아서 휙휙 날아감 ㄷㄷ " + pct,
                              pct + " 찍고 눌림 오나 보는 중. 짧게 먹고 튄다",
                              pct + " 세력 들어왔냐? 윗꼬리 달리면 바로 던짐",
                              "아 " + pct + "에 익절했는데 더 가네 ㅋㅋ 배 아프다")
                    : List.of(pct + " 설거지 당한 사람 손 ㅋㅋ",
                              "오늘 " + pct + " 반등 나오나 짧게 본다",
                              pct + " 손절선 깔아놨으니 알아서 하겠지",
                              pct + "면 체결 알림 계속 울리겠네 ㄷㄷ",
                              pct + " 떨어지는 칼날은 안 잡음. 바닥 확인하고 본다 ㄹㅇ");
            case NEWBIE -> up
                    ? List.of("오늘 " + pct + "?? 뭔 일 있음?",
                              fandom ? "최애겜 " + pct + " 실화냐 ㅋㅋㅋㅋ 기분 째짐" : pct + " 오르는 거 보고 들어옴. 뉴스 본 사람?",
                              "와 " + pct + " ㅁㅊ 뭐가 뭔지 모르겠는데 일단 개꿀",
                              "주린이인데 " + pct + "면 많이 오른 거임? ㅋㅋ",
                              pct + " 빨간불 처음 봄 ㅠㅠ 감동")
                    : List.of("오늘 " + pct + " … 이럴 땐 어케 함? ㅠ",
                              fandom ? "최애겜 " + pct + " 에바 ㅠ 그래도 팬이라 못 팖" : pct + " 빠졌네 뉴스 탭부터 보러 감",
                              "파란불 " + pct + " … 수습 때 배운 손실한도 생각나네",
                              "아 고점에 물렸다 " + pct + " ㅋㅋㅋ 웃프다",
                              pct + " 실화임? 나만 물린 거 아니지?");
        };
        return lines.get(random.nextInt(lines.size()));
    }
}
