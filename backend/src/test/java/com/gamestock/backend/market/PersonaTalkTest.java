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
                    assertTrue(line.body().contains(quote.price() > quote.prevClose() ? "+10퍼" : "-10퍼"), line.body());
                }
    }

    @Test void percentReadsLikeACommunityPost() {
        assertEquals("+4.3퍼", PersonaTalk.percent(0.043));
        assertEquals("-10퍼", PersonaTalk.percent(-0.1));
    }

    @Test void oldPostsAreRewrittenOnceWithTheSameMove() {
        Persona persona = persona("GI", Persona.Style.LONG);
        String up = PersonaTalk.rewrite(persona, "GI", "생각보다 빨리 올랐네요(+4.3%). 급하게 따라가진 않으려고요.", 7).orElseThrow();
        assertTrue(up.contains("+4.3퍼"), up);
        assertEquals(up, StockTalkService.cleanBody(up));
        String down = PersonaTalk.rewrite(persona, "BA", "오늘 -12.0%… 흔들리지만 일주일 단위로 보려고요.", 7).orElseThrow();
        assertTrue(down.contains("-12퍼"), down);
        // 이미 바뀐 글은 다시 바꾸지 않는다.
        assertTrue(PersonaTalk.rewrite(persona, "GI", up, 7).isEmpty());
    }
}
