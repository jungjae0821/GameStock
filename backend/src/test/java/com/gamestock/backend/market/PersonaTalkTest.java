package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static com.gamestock.backend.market.PersonaDecisions.Quote;
import static org.junit.jupiter.api.Assertions.*;

class PersonaTalkTest {
    private static Persona persona(String fandom, Persona.Style style) {
        Persona base = Persona.ALL.get(0);
        return new Persona(base.username(), base.nickname(), style, fandom, base.weekday(), base.weekend(),
                base.weekdayActive(), base.weekendActive(), base.weekdayActions(), base.weekendActions(), base.orderScale());
    }

    @Test void quietMarketsGetNoPost() {
        var quotes = List.of(new Quote("GI", 10_200, 11_000, 10_000, 10_100), new Quote("BA", 9_900, 11_000, 10_000, 10_000));
        assertTrue(PersonaTalk.pick(persona("UMA", Persona.Style.LONG), quotes).isEmpty());
    }

    @Test void fandomStockComesFirstAmongBigMoves() {
        var quotes = List.of(new Quote("GI", 13_000, 13_000, 10_000, 12_000), new Quote("UMA", 9_600, 11_000, 10_000, 9_800));
        assertEquals("UMA", PersonaTalk.pick(persona("UMA", Persona.Style.NEWBIE), quotes).orElseThrow().code());
        assertEquals("GI", PersonaTalk.pick(persona("BA", Persona.Style.NEWBIE), quotes).orElseThrow().code());
    }

    @Test void linesFitTheTalkBoardRulesForEveryStyle() {
        var up = new Quote("GI", 11_000, 11_000, 10_000, 10_500);
        var down = new Quote("GI", 9_000, 11_000, 10_000, 9_500);
        for (Persona.Style style : Persona.Style.values())
            for (Quote quote : List.of(up, down))
                for (int seed = 0; seed < 12; seed++) {
                    var line = PersonaTalk.line(persona("GI", style), quote, new Random(seed));
                    assertEquals("GI", line.code());
                    // 종목토론의 본문 검사(길이·링크·금칙어)를 그대로 통과해야 실제로 올라간다.
                    assertEquals(line.body(), StockTalkService.cleanBody(line.body()));
                    assertTrue(line.body().contains(quote.price() > quote.prevClose() ? "+10.0%" : "-10.0%"), line.body());
                }
    }
}
