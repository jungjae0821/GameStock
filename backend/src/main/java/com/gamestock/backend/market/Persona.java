package com.gamestock.backend.market;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 일반 회원처럼 랭킹·공개 프로필·팬덤·일일 예측에 나오는 운영 봇 한 명.
 *
 * <p>계정은 {@code password_hash='PERSONA'}라서 {@code NOT IN ('BOT','TRADER')} 필터를 그대로
 * 통과하고, {@code users.persona}에 성격이 남아 관리자 화면과 보상 정산만 이들을 가려낸다.
 * 접속 여부와 접속 시간대는 계정과 한국 날짜로 정한 난수라서 서버를 다시 띄워도 같은 하루를 산다.
 * 낮에 사는 세 명은 주말에 거의 매일, 오전부터 밤까지 길게 접속하고 평일보다 자주 사고판다.
 *
 * <p>봇의 하루는 {@link #DAY_START}(06:00)에 시작해 다음 날 06:00에 끝난다. 그래서 {@code 23:00~02:00}처럼
 * 자정을 넘는 접속도 한 번의 접속으로 다루고, 새벽 2시의 접속 여부와 평일·주말은 전날 밤을 따른다.
 * {@code orderScale}은 매수 수량에 곱하는 배수로, 호가가 얇은 새벽 봇은 작게 사서 가격을 덜 흔든다.
 */
record Persona(String username, String nickname, Style style, String fandomCode,
               List<Window> weekday, List<Window> weekend,
               double weekdayActive, double weekendActive, double weekdayActions, double weekendActions,
               double orderScale) {
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final String PASSWORD = "PERSONA";
    /** 날마다 접속 시간대를 이만큼(분) 앞뒤로 흔든다. */
    static final int WINDOW_JITTER_MINUTES = 20;
    /** 봇의 하루가 바뀌는 시각. 이보다 이른 시각은 전날 밤에서 이어진 새벽이다. */
    static final LocalTime DAY_START = LocalTime.of(6, 0);
    private static final int DAY_MINUTES = 24 * 60;

    enum Style { LONG, SCALP, NEWBIE }

    /** 접속 시간대 정의. {@code to}가 {@code from}보다 이르면 자정을 넘긴다. */
    record Window(LocalTime from, LocalTime to) {
        static Window of(String from, String to) { return new Window(LocalTime.parse(from), LocalTime.parse(to)); }
        /** 하루 시작부터 센 분. */
        int startMinute() { return sinceDayStart(from); }
        int endMinute() { int end = sinceDayStart(to); return end == 0 ? DAY_MINUTES : end; }
    }

    /** 실제 날짜와 시각으로 펼친 그날의 접속 한 번. */
    record Session(LocalDateTime from, LocalDateTime to) {
        int minutes() { return Math.max(0, (int) java.time.Duration.between(from, to).toMinutes()); }
        boolean contains(LocalDateTime at) { return !at.isBefore(from) && at.isBefore(to); }
    }

    static final List<Persona> ALL = List.of(
            // 장기투자형: 평일엔 출근 전·점심·밤에 잠깐, 주말엔 오전·오후·밤에 느긋하게 들여다본다.
            new Persona("persona_toreggi", "토레찌", Style.LONG, "UMA",
                    List.of(Window.of("07:30", "08:40"), Window.of("12:10", "12:50"), Window.of("21:00", "23:00")),
                    List.of(Window.of("09:30", "12:30"), Window.of("14:00", "17:00"), Window.of("20:30", "23:30")),
                    0.9, 0.9, 0.4, 1.0, 1),
            // 단타형: 평일엔 점심시간과 퇴근 뒤 밤, 주말엔 늦은 아침부터 자정까지 거의 붙어 있다.
            new Persona("persona_kazusa", "카즈사점심먹었습니까", Style.SCALP, "BA",
                    List.of(Window.of("11:30", "13:30"), Window.of("19:00", "23:50")),
                    List.of(Window.of("10:30", "13:30"), Window.of("14:30", "18:30"), Window.of("20:00", "23:50")),
                    0.85, 0.95, 8, 14, 1),
            // 초보형: 평일 밤에 가끔 들어오고, 주말엔 점심 무렵부터 밤까지 자주 들여다본다.
            new Persona("persona_rapi", "정실은라피", Style.NEWBIE, "GOV",
                    List.of(Window.of("20:30", "23:30")),
                    List.of(Window.of("11:00", "13:00"), Window.of("14:00", "19:00"), Window.of("20:30", "23:50")),
                    0.55, 0.95, 1.5, 4, 1),
            // 편의점 야간 알바: 손님이 뜸한 새벽에 짬짬이 단타하고, 쉬는 날에도 밤낮이 바뀐 채로 산다.
            new Persona("persona_nightclerk", "귤복복살려내", Style.SCALP, "ZZZ",
                    List.of(Window.of("00:10", "02:20"), Window.of("03:20", "05:50")),
                    List.of(Window.of("14:00", "15:30"), Window.of("22:30", "01:30")),
                    0.85, 0.6, 5, 6, 0.35),
            // 밤샘하는 대학생: 과제하다 새벽에 들어와 남들 따라 사고, 며칠씩 안 오기도 한다.
            new Persona("persona_allnighter", "스삐키네르지마세요", Style.NEWBIE, "TR",
                    List.of(Window.of("00:50", "04:00")),
                    List.of(Window.of("15:00", "16:30"), Window.of("01:30", "05:00")),
                    0.6, 0.7, 2, 3, 0.5));

    String googleUid() { return "persona:" + username; }

    /** 이 시각이 속한 봇의 하루. 06:00 전이면 전날이다. */
    static LocalDate dayOf(ZonedDateTime at) {
        return at.toLocalDateTime().minusMinutes(DAY_START.toSecondOfDay() / 60).toLocalDate();
    }

    /** 그날 접속하는지. 같은 계정·같은 날짜는 언제 물어도 같은 답이다. */
    boolean activeOn(LocalDate day) {
        boolean weekendDay = weekend(day);
        return random(day, 1).nextDouble() < (weekendDay ? weekendActive : weekdayActive);
    }

    /** 그날 접속 시간대. 날마다 최대 {@link #WINDOW_JITTER_MINUTES}분씩 앞뒤로 움직이고 봇의 하루 안에서 자른다. */
    List<Session> windowsOn(LocalDate day) {
        SplittableRandom random = random(day, 2);
        LocalDateTime start = day.atTime(DAY_START);
        List<Session> sessions = new ArrayList<>();
        for (Window window : weekend(day) ? weekend : weekday) {
            int shift = random.nextInt(WINDOW_JITTER_MINUTES * 2 + 1) - WINDOW_JITTER_MINUTES;
            int from = clamp(window.startMinute() + shift);
            int to = clamp(window.endMinute() + shift);
            sessions.add(new Session(start.plusMinutes(from), start.plusMinutes(to)));
        }
        return List.copyOf(sessions);
    }

    /** 지금이 접속한 날의 접속 시간대 안인지. */
    boolean onlineAt(ZonedDateTime at) {
        LocalDate day = dayOf(at);
        if (!activeOn(day)) return false;
        LocalDateTime time = at.toLocalDateTime();
        return windowsOn(day).stream().anyMatch(session -> session.contains(time));
    }

    /** 접속 중 1분마다 매매를 시도할 확률. 평일·주말 하루 평균 매매 횟수를 그날 접속 시간에 고르게 나눈다. */
    double actionChancePerMinute(LocalDate day) {
        int minutes = windowsOn(day).stream().mapToInt(Session::minutes).sum();
        return minutes == 0 ? 0 : Math.min(1, (weekend(day) ? weekendActions : weekdayActions) / minutes);
    }

    private static int sinceDayStart(LocalTime time) {
        int minute = (time.toSecondOfDay() - DAY_START.toSecondOfDay()) / 60;
        return minute < 0 ? minute + DAY_MINUTES : minute;
    }

    private static int clamp(int minute) {
        return Math.max(0, Math.min(DAY_MINUTES - 1, minute));
    }

    private static boolean weekend(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /**
     * java.util.Random은 시드가 조금만 달라도 첫 난수가 거의 같아서, 날짜만 바뀌는 시드로는 접속 여부가
     * 몇 주씩 같은 값으로 굳었다. SplittableRandom은 시드를 섞어서 첫 난수부터 날마다 고르게 퍼진다.
     */
    private SplittableRandom random(LocalDate day, int purpose) {
        return new SplittableRandom(username.hashCode() * 0x9E3779B97F4A7C15L ^ day.toEpochDay() * 31 + purpose);
    }
}
