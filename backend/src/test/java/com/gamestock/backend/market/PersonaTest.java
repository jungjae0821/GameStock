package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static com.gamestock.backend.market.PersonaDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonaTest {
    private static Persona persona(Persona.Style style) {
        return Persona.ALL.stream().filter(p -> p.style() == style).findFirst().orElseThrow();
    }

    @Test void personasLookLikeMembersSignedUpThroughTheApp() {
        assertEquals(3, Persona.ALL.size());
        assertEquals(3, new HashSet<>(Persona.ALL.stream().map(Persona::nickname).toList()).size());
        for (Persona p : Persona.ALL) {
            // 회원 닉네임 규칙(2~10자)을 벗어나면 랭킹에서 바로 티가 난다.
            assertTrue(p.nickname().length() >= 2 && p.nickname().length() <= 10, p.nickname());
            assertTrue(StockCatalog.initialPrices().containsKey(p.fandomCode()), p.fandomCode());
        }
    }

    @Test void theSameDayAlwaysGetsTheSameSchedule() {
        Persona scalper = persona(Persona.Style.SCALP);
        LocalDate day = LocalDate.of(2026, 11, 16);
        assertEquals(scalper.activeOn(day), scalper.activeOn(day));
        assertEquals(scalper.windowsOn(day), scalper.windowsOn(day));
    }

    @Test void windowsMoveALittleButStayInsideTheDay() {
        for (Persona p : Persona.ALL) {
            for (int offset = 0; offset < 60; offset++) {
                LocalDate day = LocalDate.of(2026, 11, 1).plusDays(offset);
                for (Persona.Window window : p.windowsOn(day)) {
                    assertTrue(window.from().isBefore(window.to()), p.username() + " " + day + " " + window);
                    assertTrue(window.minutes() > 0);
                }
                double chance = p.actionChancePerMinute(day);
                assertTrue(chance > 0 && chance <= 1);
            }
        }
    }

    @Test void personasSkipSomeDaysAndAreOfflineAtFourInTheMorning() {
        for (Persona p : Persona.ALL) {
            int active = 0;
            for (int offset = 0; offset < 120; offset++) {
                LocalDate day = LocalDate.of(2026, 9, 1).plusDays(offset);
                if (p.activeOn(day)) active++;
                assertFalse(p.onlineAt(day.atTime(LocalTime.of(4, 0)).atZone(Persona.ZONE)));
            }
            assertTrue(active > 30 && active < 120, p.username() + " active " + active);
        }
    }

    @Test void activeDaysFollowEachRateInsteadOfStickingForWeeks() {
        for (Persona p : Persona.ALL) {
            int weekdays = 0, activeWeekdays = 0, weekends = 0, activeWeekends = 0;
            for (int offset = 0; offset < 168; offset++) {
                LocalDate day = LocalDate.of(2026, 11, 2).plusDays(offset);
                boolean weekend = day.getDayOfWeek().getValue() >= 6;
                boolean on = p.activeOn(day);
                if (weekend) { weekends++; if (on) activeWeekends++; } else { weekdays++; if (on) activeWeekdays++; }
            }
            double weekdayRate = activeWeekdays / (double) weekdays, weekendRate = activeWeekends / (double) weekends;
            assertEquals(p.weekdayActive(), weekdayRate, 0.15, p.username() + " 평일 접속률");
            assertEquals(p.weekendActive(), weekendRate, 0.15, p.username() + " 주말 접속률");
        }
    }

    @Test void weekendsGetLongerOnlineTimeAndMoreTrades() {
        LocalDate friday = LocalDate.of(2026, 11, 20), saturday = friday.plusDays(1);
        for (Persona p : Persona.ALL) {
            int weekday = p.windowsOn(friday).stream().mapToInt(Persona.Window::minutes).sum();
            int weekend = p.windowsOn(saturday).stream().mapToInt(Persona.Window::minutes).sum();
            assertTrue(weekend >= 480, p.username() + " 주말 접속 " + weekend + "분");
            assertTrue(weekend > weekday, p.username());
            assertTrue(p.weekendActions() > p.weekdayActions(), p.username());
            assertTrue(p.weekendActive() >= 0.9, p.username());
        }
    }

    // ── 매매 판단 ──────────────────────────────────────────

    private static final List<Quote> FLAT = List.of(
            new Quote("UMA", 10_000, 10_000, 10_000, 10_000),
            new Quote("BA", 20_000, 20_000, 20_000, 20_000),
            new Quote("GOV", 5_000, 5_000, 5_000, 5_000));

    @Test void scalperTakesProfitAndCutsLossesBeforeBuying() {
        Persona scalper = persona(Persona.Style.SCALP);
        var winner = new Account(500_000, 1_000_000, List.of(new Holding("BA", 20, 20_000, 20_900, 10)));
        assertEquals(new Order("BA", "SELL", 20, "TAKE_PROFIT"), decide(scalper, winner, FLAT, false, new Random(1)).orElseThrow());
        var loser = new Account(500_000, 1_000_000, List.of(new Holding("BA", 20, 20_000, 19_300, 10)));
        assertEquals("STOP_LOSS", decide(scalper, loser, FLAT, false, new Random(1)).orElseThrow().reason());
        var stale = new Account(500_000, 1_000_000, List.of(new Holding("BA", 20, 20_000, 20_100, 200)));
        assertEquals("TIME_EXIT", decide(scalper, stale, FLAT, false, new Random(1)).orElseThrow().reason());
    }

    @Test void scalperBuysARisingStockOnlyWhenNotBlocked() {
        Persona scalper = persona(Persona.Style.SCALP);
        List<Quote> rising = List.of(new Quote("UMA", 10_300, 10_300, 10_000, 10_000), FLAT.get(1));
        var cash = new Account(1_000_000, 1_000_000, List.of());
        Order order = decide(scalper, cash, rising, false, new Random(3)).orElseThrow();
        assertEquals("UMA", order.code());
        assertEquals("BUY", order.side());
        assertTrue(order.quantity() * 10_300L <= 350_000L);
        assertTrue(decide(scalper, cash, rising, true, new Random(3)).isEmpty());
        assertTrue(decide(scalper, cash, FLAT, false, new Random(3)).isEmpty());
    }

    @Test void longTermBuysDipsAndSellsHalfAfterABigGain() {
        Persona investor = persona(Persona.Style.LONG);
        List<Quote> dip = List.of(new Quote("BA", 17_000, 20_000, 17_500, 17_000), FLAT.get(0));
        var cash = new Account(1_000_000, 1_000_000, List.of());
        Order buy = decide(investor, cash, dip, false, new Random(5)).orElseThrow();
        assertEquals("BA", buy.code());
        assertTrue(buy.quantity() * 17_000L <= 150_000L);
        var gain = new Account(300_000, 1_000_000, List.of(new Holding("UMA", 41, 8_000, 10_000, 30_000)));
        assertEquals(new Order("UMA", "SELL", 20, "TAKE_HALF"), decide(investor, gain, FLAT, false, new Random(5)).orElseThrow());
        // 현금이 순자산의 25% 아래면 더 사지 않는다.
        var invested = new Account(200_000, 1_000_000, List.of(new Holding("UMA", 80, 10_000, 10_000, 30_000)));
        assertTrue(decide(investor, invested, dip, false, new Random(5)).isEmpty());
    }

    @Test void newbieChasesTheDaysTopGainerAndSellsSmallProfitsEarly() {
        Persona newbie = persona(Persona.Style.NEWBIE);
        List<Quote> surge = List.of(new Quote("BA", 23_000, 23_000, 20_000, 22_000), FLAT.get(0), FLAT.get(2));
        Order chase = decide(newbie, new Account(1_000_000, 1_000_000, List.of()), surge, false, new Random(7)).orElseThrow();
        assertEquals(new Order("BA", "BUY", chase.quantity(), "CHASE"), chase);
        var smallWin = new Account(100_000, 1_000_000, List.of(new Holding("GOV", 100, 5_000, 5_600, 600)));
        assertEquals("EARLY_PROFIT", decide(newbie, smallWin, FLAT, false, new Random(7)).orElseThrow().reason());
    }

    @Test void ordersNeverExceedTheMemberOrderLimit() {
        Persona newbie = persona(Persona.Style.NEWBIE);
        List<Quote> cheap = List.of(new Quote("GOV", 10, 10, 9, 10));
        Order order = decide(newbie, new Account(50_000_000, 50_000_000, List.of()), cheap, false, new Random(9)).orElseThrow();
        assertEquals(MAX_ORDER_QUANTITY, order.quantity());
    }

    @Test void predictionsFollowEachStyle() {
        List<Quote> quotes = List.of(new Quote("UMA", 8_000, 10_000, 8_000, 8_000), new Quote("BA", 21_000, 21_000, 20_000, 20_000));
        assertEquals("UMA", PersonaTraderService.predictionPick(persona(Persona.Style.LONG), quotes));
        assertEquals("BA", PersonaTraderService.predictionPick(persona(Persona.Style.SCALP), quotes));
        assertEquals("GOV", PersonaTraderService.predictionPick(persona(Persona.Style.NEWBIE), quotes));
    }
}
