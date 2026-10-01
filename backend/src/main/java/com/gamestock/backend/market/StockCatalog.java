package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** The listings shared with the web universe; all prices are simulated. */
public final class StockCatalog {
    private StockCatalog() {}

    private record Listing(String code, String name, String developer, String genre) {}

    private static final List<Listing> LISTINGS = List.of(
            new Listing("UMA", "우마무스메 프리티더비", "Cygames", "RPG"),
            new Listing("BA", "블루 아카이브", "Nexon", "RPG"),
            new Listing("GOV", "승리의 여신: 니케", "ShiftUp", "RPG"),
            new Listing("ZZZ", "젠레스 존 제로", "HoYoverse", "액션"),
            new Listing("GI", "원신", "HoYoverse", "오픈월드"),
            new Listing("SR", "붕괴: 스타레일", "HoYoverse", "RPG"),
            new Listing("AK", "명일방주: 엔드필드", "Hypergryph", "전략 RPG"),
            new Listing("WH", "명조: 워더링 웨이브", "Kuro Games", "액션"),
            new Listing("PX", "쿠키런: 킹덤", "Devsisters", "RPG"),
            new Listing("LT", "림버스 컴퍼니", "Project Moon", "RPG"),
            new Listing("ES", "에픽세븐", "Smilegate", "RPG"),
            new Listing("MH", "몬스터헌터 와일즈", "Capcom", "액션"),
            new Listing("EL", "오버워치", "Blizzard Entertainment", "FPS"),
            new Listing("PW", "팰월드", "Pocketpair", "생존"),
            new Listing("SD", "트릭컬 리바이브", "EPID Games", "RPG"));

    /** Add missing games/stocks without changing existing prices or trading data. */
    public static void ensureListings(JdbcTemplate jdbc) {
        replaceListing(jdbc, "AK", "명일방주", "명일방주: 엔드필드", "Hypergryph", "전략 RPG");
        replaceListing(jdbc, "EL", "엘든 링", "오버워치", "Blizzard Entertainment", "FPS");
        replaceListing(jdbc, "PX", "페르소나5: 더 팬텀 X", "쿠키런: 킹덤", "Devsisters", "RPG");
        replaceListing(jdbc, "SD", "스타듀 밸리", "트릭컬 리바이브", "EPID Games", "RPG");
        for (Listing listing : LISTINGS) {
            jdbc.update("""
                    INSERT INTO games (name, developer, genre)
                    SELECT ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM games WHERE name = ?)
                    """, listing.name(), listing.developer(), listing.genre(), listing.name());
            jdbc.update("""
                    INSERT INTO stocks (game_id, stock_code, current_price, previous_price, total_volume)
                    SELECT g.id, ?, ?, ?, 0 FROM games g
                    WHERE g.name = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM stocks s JOIN games existing ON existing.id = s.game_id
                          WHERE s.stock_code = ? OR existing.name = ?
                      )
                    ORDER BY g.id LIMIT 1
                    """, listing.code(), 10_000L, 10_000L, listing.name(), listing.code(), listing.name());
        }
    }

    /** Retain stock IDs, codes, holdings and history when replacing a game. */
    private static void replaceListing(JdbcTemplate jdbc, String code, String oldName,
                                       String newName, String developer, String genre) {
        jdbc.update("""
                UPDATE games g JOIN stocks s ON s.game_id = g.id
                SET g.name = ?, g.developer = ?, g.genre = ?
                WHERE s.stock_code = ? AND g.name IN (?, ?)
                """, newName, developer, genre, code, oldName, newName);
    }
}
