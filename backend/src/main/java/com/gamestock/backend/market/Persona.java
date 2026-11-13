package com.gamestock.backend.market;

import java.time.DayOfWeek;
import java.time.LocalDate;
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
 * 주말에는 셋 다 거의 매일, 오전부터 밤까지 길게 접속하고 평일보다 자주 사고판다.
 */
record Persona(String username, String nickname, Style style, String fandomCode,
               List<Window> weekday, List<Window> weekend,
               double weekdayActive, double weekendActive, double weekdayActions, double weekendActions) {
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final String PASSWORD = "PERSONA";
    /** 날마다 접속 시간대를 이만큼(분) 앞뒤로 흔든다. */
    static final int WINDOW_JITTER_MINUTES = 20;

    enum Style { LONG, SCALP, NEWBIE }

    record Window(LocalTime from, LocalTime to) {
        static Window of(String from, String to) { return new Window(LocalTime.parse(from), LocalTime.parse(to)); }
        int minutes() { return Math.max(0, (int) java.time.Duration.between(from, to).toMinutes()); }
    }

    static final List<Persona> ALL = List.of(
            // 장기투자형: 평일엔 출근 전·점심·밤에 잠깐, 주말엔 오전·오후·밤에 느긋하게 들여다본다.
            new Persona("persona_toreggi", "토레찌", Style.LONG, "UMA",
                    List.of(Window.of("07:30", "08:40"), Window.of("12:10", "12:50"), Window.of("21:00", "23:00")),
                    List.of(Window.of("09:30", "12:30"), Window.of("14:00", "17:00"), Window.of("20:30", "23:30")),
                    0.9, 0.9, 0.4, 1.0),
            // 단타형: 평일엔 점심시간과 퇴근 뒤 밤, 주말엔 늦은 아침부터 자정까지 거의 붙어 있다.
            new Persona("persona_kazusa", "카즈사점심먹었습니까", Style.SCALP, "BA",
                    List.of(Window.of("11:30", "13:30"), Window.of("19:00", "23:50")),
                    List.of(Window.of("10:30", "13:30"), Window.of("14:30", "18:30"), Window.of("20:00", "23:50")),
                    0.85, 0.95, 8, 14),
            // 초보형: 평일 밤에 가끔 들어오고, 주말엔 점심 무렵부터 밤까지 자주 들여다본다.
            new Persona("persona_rapi", "정실은라피", Style.NEWBIE, "GOV",
                    List.of(Window.of("20:30", "23:30")),
                    List.of(Window.of("11:00", "13:00"), Window.of("14:00", "19:00"), Window.of("20:30", "23:50")),
                    0.55, 0.95, 1.5, 4));

    String googleUid() { return "persona:" + username; }

    /** 그날 접속하는지. 같은 계정·같은 날짜는 언제 물어도 같은 답이다. */
    boolean activeOn(LocalDate day) {
        boolean weekendDay = weekend(day);
        return random(day, 1).nextDouble() < (weekendDay ? weekendActive : weekdayActive);
    }

    /** 그날 접속 시간대. 날마다 최대 {@link #WINDOW_JITTER_MINUTES}분씩 앞뒤로 움직인다. */
    List<Window> windowsOn(LocalDate day) {
        SplittableRandom random = random(day, 2);
        List<Window> shifted = new ArrayList<>();
        for (Window window : weekend(day) ? weekend : weekday) {
            int shift = random.nextInt(WINDOW_JITTER_MINUTES * 2 + 1) - WINDOW_JITTER_MINUTES;
            // LocalTime.plusMinutes는 자정을 넘기면 한 바퀴 돌므로 분 단위로 그날 안에 자른다.
            shifted.add(new Window(minuteOfDay(window.from(), shift), minuteOfDay(window.to(), shift)));
        }
        return List.copyOf(shifted);
    }

    /** 지금이 접속한 날의 접속 시간대 안인지. */
    boolean onlineAt(ZonedDateTime at) {
        LocalDate day = at.toLocalDate();
        if (!activeOn(day)) return false;
        LocalTime time = at.toLocalTime();
        for (Window window : windowsOn(day))
            if (!time.isBefore(window.from()) && time.isBefore(window.to())) return true;
        return false;
    }

    /** 접속 중 1분마다 매매를 시도할 확률. 평일·주말 하루 평균 매매 횟수를 그날 접속 시간에 고르게 나눈다. */
    double actionChancePerMinute(LocalDate day) {
        int minutes = windowsOn(day).stream().mapToInt(Window::minutes).sum();
        return minutes == 0 ? 0 : Math.min(1, (weekend(day) ? weekendActions : weekdayActions) / minutes);
    }

    private static LocalTime minuteOfDay(LocalTime time, int shift) {
        int minute = Math.max(0, Math.min(23 * 60 + 59, time.toSecondOfDay() / 60 + shift));
        return LocalTime.of(minute / 60, minute % 60);
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
