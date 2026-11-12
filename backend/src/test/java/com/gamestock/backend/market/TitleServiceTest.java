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
    private String equipped;
    private boolean lastWeekOpen;
    private final Map<Long, Long> adjustedAssets = new LinkedHashMap<>();
    private final List<Long> tradedLastWeek = new ArrayList<>();
    /** Closing snapshot of last week; null when none was taken. */
    private java.sql.Timestamp closingAt;
    private final Map<Long, Long> closingAssets = new LinkedHashMap<>();
    /** user id → weekly titles awarded to that user. */
    private final Map<Long, List<String>> weeklyWinners = new LinkedHashMap<>();
    /** promo_codes rows by code, and the "code:user" pairs in promo_redemptions. */
    private final Map<String, Map<String, Object>> promos = new LinkedHashMap<>(Map.of("syuen", promo(0, "special_syuangel,special_misilis_holder,special_syuaga", null)));
    private final List<String> redemptions = new ArrayList<>();
    private final List<Long> cashPaid = new ArrayList<>();

    private static Map<String, Object> promo(long cash, String titleIds, Integer maxUses) {
        Map<String, Object> row = new HashMap<>();
        row.put("active", true);
        row.put("reward_cash", cash);
        row.put("title_ids", titleIds);
        row.put("max_uses", maxUses);
        row.put("created_by", 1L);
        return row;
    }

    private TitleService service() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class, call -> {
            String method = call.getMethod().getName();
            Object[] args = call.getArguments();
            String sql = args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
            if(method.equals("queryForObject") && sql.startsWith("SELECT COALESCE((SELECT SUM(GREATEST(dp.mark_value"))return 0L;
            // Text blocks drop trailing spaces, so concatenated SQL must not glue keywords together.
            assertFalse(java.util.regex.Pattern.compile("\\b(WHERE|AND|FROM)[^\\s(]").matcher(sql).find(), sql);
            if (method.equals("update")) {
                if (sql.startsWith("INSERT INTO promo_redemptions")) {
                    redemptions.add(args[1] + ":" + args[2]);
                    return 1;
                }
                if (sql.startsWith("UPDATE users SET cash = cash + ?")) {
                    cashPaid.add((Long) args[1]);
                    return 1;
                }
                if (sql.startsWith("INSERT IGNORE INTO user_titles"))
                    return titles.putIfAbsent((String) args[2], 1) == null ? 1 : 0;
                if (sql.contains("INSERT INTO user_titles")) {
                    titles.merge((String) args[2], 1, Integer::sum);
                    weeklyWinners.computeIfAbsent((Long) args[1], id -> new ArrayList<>()).add((String) args[2]);
                    return 1;
                }
                if (sql.startsWith("UPDATE user_titles SET equipped = (title_id = ?)")) {
                    equipped = (String) args[1];
                    return titles.size();
                }
                if (sql.startsWith("UPDATE user_titles SET equipped = FALSE")) {
                    equipped = null;
                    return 1;
                }
                if (sql.startsWith("UPDATE weekly_return_weeks")) {
                    boolean claimed = lastWeekOpen;
                    lastWeekOpen = false;
                    return claimed ? 1 : 0;
                }
                return 1;
            }
            if (method.equals("queryForObject") && sql.contains("FROM promo_redemptions WHERE code = ? AND user_id = ?"))
                return redemptions.contains(args[2] + ":" + args[3]) ? 1 : 0;
            if (method.equals("queryForObject") && sql.contains("FROM promo_redemptions WHERE code = ?"))
                return (int) redemptions.stream().filter(entry -> entry.startsWith(args[2] + ":")).count();
            if (method.equals("queryForList")) {
                if (sql.contains("FROM promo_codes")) {
                    var row = promos.get((String) args[1]);
                    return row == null ? List.of() : List.of(row);
                }
                if (sql.contains("FROM user_titles")) return new ArrayList<>(titles.keySet());
                if (sql.contains("FROM trades")) return tradedLastWeek;
                return List.of();
            }
            if (method.equals("query")) {
                if (sql.startsWith("SELECT closing_at FROM weekly_return_weeks"))
                    return closingAt == null ? List.of() : List.of(closingAt);
                if (sql.startsWith("SELECT user_id, closing_asset") && args[1] instanceof RowCallbackHandler handler) {
                    for (var entry : closingAssets.entrySet()) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getLong(1)).thenReturn(entry.getKey());
                        when(rs.getLong(2)).thenReturn(entry.getValue());
                        handler.processRow(rs);
                    }
                    return null;
                }
                if (sql.contains("FROM users u WHERE u.id = ?")) {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong(anyString())).thenAnswer(i -> ((Number) user.getOrDefault(i.getArgument(0), 0L)).longValue());
                    when(rs.getInt(anyString())).thenAnswer(i -> ((Number) user.getOrDefault(i.getArgument(0), 0)).intValue());
                    when(rs.getBoolean(anyString())).thenAnswer(i -> (Boolean) user.getOrDefault(i.getArgument(0), false));
                    return List.of(((RowMapper<?>) args[1]).mapRow(rs, 0));
                }
                // Checked before the portfolio branch: the display query also joins portfolios.
                if (sql.contains("u.id IN (") && args[1] instanceof RowCallbackHandler handler) {
                    for (Object id : java.util.Arrays.copyOfRange(args, 2, args.length)) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getLong("id")).thenReturn((Long) id);
                        when(rs.getString("nickname")).thenReturn("user" + id);
                        when(rs.getString("profile_image_url")).thenReturn(null);
                        when(rs.getLong("total_asset")).thenReturn(adjustedAssets.get((Long) id));
                        handler.processRow(rs);
                    }
                    return null;
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
                        when(rs.getBoolean(5)).thenReturn(entry.getKey().equals(equipped));
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
        assertEquals(42, first.totalCount());
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

    @Test void weeklyTitlesRankTheStoredClosingAssetsNotTheLiveOnes() {
        TitleService service = service();
        // After the close user 1 surged and user 2 fell; the award must ignore that.
        adjustedAssets.put(1L, 3_000_000L);
        adjustedAssets.put(2L, 1_000_000L);
        closingAt = java.sql.Timestamp.from(Instant.parse("2026-10-11T14:59:00Z"));
        closingAssets.put(1L, 1_100_000L);
        closingAssets.put(2L, 1_500_000L);
        tradedLastWeek.addAll(List.of(1L, 2L));
        lastWeekOpen = true;
        service.awardWeeklyTitles();
        assertEquals(List.of("weekly_1", "weekly_2", "weekly_3"), weeklyWinners.get(2L));
        assertEquals(List.of("weekly_2", "weekly_3"), weeklyWinners.get(1L));
    }

    @Test void weeklyRankingRanksTradersByReturnAndAddsViewerRow() {
        TitleService service = service();
        adjustedAssets.put(1L, 1_100_000L);
        adjustedAssets.put(2L, 1_300_000L);
        adjustedAssets.put(3L, 900_000L);
        adjustedAssets.put(4L, 5_000_000L);
        tradedLastWeek.addAll(List.of(1L, 2L, 3L));
        var ranking = service.weeklyRanking(3L);
        // User 4 did not trade this week, so a large asset does not place them.
        assertEquals(3, ranking.participants());
        assertEquals(List.of("user2", "user1", "user3"), ranking.entries().stream().map(TitleService.WeeklyRankingEntry::nickname).toList());
        assertEquals(30.0, ranking.entries().get(0).weeklyReturnPercent(), 1e-9);
        assertEquals(3, ranking.me().rank());
        assertEquals("2026-10-12", ranking.weekStart());
        assertEquals("2026-10-18", ranking.weekEnd());
        assertEquals("2026-10-18T15:00:00Z", ranking.awardAt());
        assertNull(service.weeklyRanking(4L).me());
    }

    @Test void onlyOwnedTitlesCanBeEquippedAndOneAtATime() {
        titles.put("newbie", 1);
        titles.put("ant", 1);
        TitleService service = service();
        var status = service.equip(7L, "ant");
        assertEquals(List.of("ant"), status.titles().stream().filter(TitleService.UserTitle::equipped).map(TitleService.UserTitle::id).toList());
        status = service.equip(7L, "newbie");
        assertEquals(List.of("newbie"), status.titles().stream().filter(TitleService.UserTitle::equipped).map(TitleService.UserTitle::id).toList());
        assertThrows(IllegalArgumentException.class, () -> service.equip(7L, "asset_100b"));
        assertThrows(IllegalArgumentException.class, () -> service.equip(7L, "unknown"));
        assertEquals("newbie", equipped);
        status = service.equip(7L, "");
        assertTrue(status.titles().stream().noneMatch(TitleService.UserTitle::equipped));
    }

    @Test void promoCodeGrantsSpecialTitlesOnce() {
        user.put("cash", 1_000_000L);
        TitleService service = service();
        assertThrows(IllegalArgumentException.class, () -> service.redeem(7L, "nope"));
        var redeemed = service.redeem(7L, "  SYUEN ");
        assertTrue(titles.keySet().containsAll(List.of("special_syuangel", "special_misilis_holder", "special_syuaga")));
        assertTrue(redeemed.titles().stream().filter(title -> title.category().equals("특별")).allMatch(TitleService.UserTitle::owned));
        assertThrows(IllegalArgumentException.class, () -> service.redeem(7L, "syuen"));
    }

    @Test void promoCodePaysCashOncePerAccountAndStopsAtItsLimit() {
        user.put("cash", 1_000_000L);
        promos.put("gift", promo(300_000, "", 1));
        TitleService service = service();
        var redeemed = service.redeem(7L, "GIFT");
        assertEquals(300_000L, redeemed.rewardCash());
        assertEquals(List.of(300_000L), cashPaid);
        assertThrows(IllegalArgumentException.class, () -> service.redeem(7L, "gift"));
        var spent = assertThrows(IllegalArgumentException.class, () -> service.redeem(8L, "gift"));
        assertTrue(spent.getMessage().contains("소진"));
        promos.get("syuen").put("active", false);
        assertThrows(IllegalArgumentException.class, () -> service.redeem(7L, "syuen"));
    }

    @Test void allCollectorNeedsSpecialTitles() {
        user.put("cash", 1_000_000L);
        for (TitleService.TitleDefinition title : TitleService.TITLES)
            if (!title.category().equals("특별") && !title.id().equals("collector_all")) titles.put(title.id(), 1);
        TitleService service = service();
        service.check(7L);
        assertFalse(titles.containsKey("collector_all"));
        service.redeem(7L, "syuen");
        assertTrue(titles.containsKey("collector_all"));
    }
}
