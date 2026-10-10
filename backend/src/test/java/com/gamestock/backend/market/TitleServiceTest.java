package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TitleServiceTest {
    /** user_titles rows: title id → win count. */
    private final Map<String, Integer> titles = new LinkedHashMap<>();
    private final Map<String, Object> user = new HashMap<>();
    /** Each position: {quantity, market value}. */
    private final List<long[]> positions = new ArrayList<>();
    private long maxDailyTrades;
    private boolean lastWeekOpen;
    private final Map<Long, Long> adjustedAssets = new LinkedHashMap<>();
    private final List<Long> tradedLastWeek = new ArrayList<>();

    private TitleService service() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class, call -> {
            String method = call.getMethod().getName();
            Object[] args = call.getArguments();
            String sql = args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
            if (method.equals("update")) {
                if (sql.startsWith("INSERT IGNORE INTO user_titles"))
                    return titles.putIfAbsent((String) args[2], 1) == null ? 1 : 0;
                if (sql.contains("INSERT INTO user_titles")) {
                    titles.merge((String) args[2], 1, Integer::sum);
                    return 1;
                }
                if (sql.startsWith("UPDATE weekly_return_weeks")) {
                    boolean claimed = lastWeekOpen;
                    lastWeekOpen = false;
                    return claimed ? 1 : 0;
                }
                return 1;
            }
            if (method.equals("queryForList")) {
                if (sql.contains("FROM user_titles")) return new ArrayList<>(titles.keySet());
                if (sql.contains("FROM trades")) return tradedLastWeek;
                return List.of();
            }
            if (method.equals("query")) {
                if (sql.contains("FROM users u WHERE u.id = ?")) {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong(anyString())).thenAnswer(i -> ((Number) user.getOrDefault(i.getArgument(0), 0L)).longValue());
                    when(rs.getInt(anyString())).thenAnswer(i -> ((Number) user.getOrDefault(i.getArgument(0), 0)).intValue());
                    when(rs.getBoolean(anyString())).thenAnswer(i -> (Boolean) user.getOrDefault(i.getArgument(0), false));
                    return List.of(((RowMapper<?>) args[1]).mapRow(rs, 0));
                }
                if (sql.contains("FROM portfolios p JOIN stocks s") && args[1] instanceof RowCallbackHandler handler) {
                    for (long[] position : positions) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getInt(1)).thenReturn((int) position[0]);
                        when(rs.getLong(2)).thenReturn(position[1]);
                        handler.processRow(rs);
                    }
                    return null;
                }
                if (sql.startsWith("SELECT title_id, win_count") && args[1] instanceof RowCallbackHandler handler) {
                    for (var entry : titles.entrySet()) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString(1)).thenReturn(entry.getKey());
                        when(rs.getInt(2)).thenReturn(entry.getValue());
                        handler.processRow(rs);
                    }
                    return null;
                }
                if (sql.contains("FROM trades")) return maxDailyTrades == 0 ? List.of() : List.of(maxDailyTrades);
                if (sql.contains("adjusted_asset")) {
                    List<Object> rows = new ArrayList<>();
                    for (var entry : adjustedAssets.entrySet()) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getLong("id")).thenReturn(entry.getKey());
                        when(rs.getLong("adjusted_asset")).thenReturn(entry.getValue());
                        rows.add(((RowMapper<?>) args[1]).mapRow(rs, rows.size()));
                    }
                    return rows;
                }
                return args[1] instanceof RowCallbackHandler ? null : List.of();
            }
            return RETURNS_DEFAULTS.answer(call);
        });
        TitleService service = new TitleService(jdbc, mock(PlatformTransactionManager.class));
        // Monday 2026-10-12 00:05 KST.
        service.useClock(Clock.fixed(Instant.parse("2026-10-11T15:05:00Z"), ZoneOffset.UTC));
        return service;
    }

    @Test void newAccountReceivesOnlyNewbie() {
        user.put("cash", 1_000_000L);
        TitleService service = service();
        var first = service.check(7L);
        assertEquals(List.of("newbie"), List.copyOf(titles.keySet()));
        assertTrue(first.titles().stream().anyMatch(title -> title.id().equals("newbie") && title.owned()));
        assertEquals(1, first.ownedCount());
        assertEquals(30, first.totalCount());
    }

    @Test void holdingsAssetsAndActivityGrantMatchingTitles() {
        user.put("cash", 3_000_000L);
        user.put("nickname_changed", true);
        user.put("max_streak", 30);
        positions.add(new long[]{160, 30_000_000L});
        positions.add(new long[]{1, 10_000L});
        maxDailyTrades = 12;
        service().check(7L);
        assertTrue(titles.keySet().containsAll(List.of("newbie", "ant", "asset_30m", "holder_50", "holder_150",
                "starter", "attend_7", "attend_30", "trades_10", "diversify", "all_in", "collector_5", "collector_10")));
        assertFalse(titles.containsKey("asset_50m"));
        assertFalse(titles.containsKey("attend_180"));
        assertFalse(titles.containsKey("trades_100"));
        assertFalse(titles.containsKey("collector_15"));
    }

    @Test void weeklyTitlesCountWinsAsNGwanwang() {
        TitleService service = service();
        adjustedAssets.put(1L, 2_000_000L);
        adjustedAssets.put(2L, 1_500_000L);
        adjustedAssets.put(3L, 1_200_000L);
        adjustedAssets.put(4L, 3_000_000L);
        tradedLastWeek.addAll(List.of(1L, 2L, 3L));
        lastWeekOpen = true;
        service.awardWeeklyTitles();
        // The fixture keeps one title map, so all three winners share it: 1위 once, 2위 twice, 3위 three times.
        assertEquals(1, titles.get("weekly_1"));
        assertEquals(2, titles.get("weekly_2"));
        assertEquals(3, titles.get("weekly_3"));
        // A second tick in the same week must not award again.
        service.awardWeeklyTitles();
        assertEquals(1, titles.get("weekly_1"));
    }
}
