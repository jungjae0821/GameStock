package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionRewardTest {
    private final Set<String> claims = new HashSet<>();
    private final AtomicLong cash = new AtomicLong(1_000_000L);
    private final AtomicLong wallet = new AtomicLong();
    private final AtomicLong watchlistCount = new AtomicLong();

    private MarketService market() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class, call -> {
            String method = call.getMethod().getName();
            Object[] args = call.getArguments();
            String sql = args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
            if (method.equals("update")) {
                if (sql.startsWith("INSERT IGNORE INTO mission_rewards")) {
                    assertEquals(10_000L, args[4]);
                    assertTrue(sql.contains("rewarded_on"));
                    return claims.add(args[2] + "/" + args[3]) ? 1 : 0;
                }
                if (sql.startsWith("UPDATE users SET cash")) cash.addAndGet((Long) args[1]);
                if (sql.contains("UPDATE career_state SET personal_cash = personal_cash + ?")) wallet.addAndGet((Long) args[1]);
                return 1;
            }
            if (method.equals("queryForObject")) {
                if (sql.startsWith("SELECT cash")) return cash.get();
                if (sql.contains("FROM user_watchlists")) return watchlistCount.get();
                return 0L;
            }
            if (method.equals("query") || method.equals("queryForList")) return List.of();
            return RETURNS_DEFAULTS.answer(call);
        });
        return new MarketService(mock(ApplicationEventPublisher.class), jdbc,
                mock(UserFeatureService.class), mock(TradingProtectionService.class));
    }

    @Test void eachMissionPaysTenThousandToTheWalletOnlyOncePerDay() {
        watchlistCount.set(1);
        MarketService market = market();
        List<String> today = MarketService.dailyMissionIds(LocalDate.now(ZoneId.of("Asia/Seoul")));
        assertEquals(3, today.size());
        for (String id : today) {
            var first = market.rewardMission(id, 7L);
            assertTrue(first.awarded());
            assertEquals(10_000L, first.rewardCash());
            var second = market.rewardMission(id, 7L);
            assertFalse(second.awarded());
            assertEquals(0L, second.rewardCash());
        }
        assertEquals(30_000L, wallet.get());
        assertEquals(1_000_000L, cash.get(), "mission rewards never touch the trading account");
        assertThrows(IllegalArgumentException.class, () -> market.rewardMission("unknown", 7L));
        assertEquals(30_000L, wallet.get());
    }

    @Test void twentyMissionsExistAndThreeAreDrawnPerKoreanDay() {
        assertEquals(20, MarketService.MISSION_IDS.size());
        assertEquals(20, new HashSet<>(MarketService.MISSION_IDS).size());
        LocalDate day = LocalDate.of(2026, 10, 8);
        List<String> today = MarketService.dailyMissionIds(day);

        assertEquals(3, today.size());
        assertEquals(3, new HashSet<>(today).size());
        assertTrue(MarketService.MISSION_IDS.containsAll(today));
        assertEquals(today, MarketService.dailyMissionIds(day), "the draw must be stable within a day");

        Set<List<String>> draws = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < 60; offset++) {
            List<String> ids = MarketService.dailyMissionIds(day.plusDays(offset));
            draws.add(ids);
            seen.addAll(ids);
        }
        assertTrue(draws.size() > 50, "daily draws should vary");
        assertEquals(20, seen.size(), "every mission should come up over two months");
    }

    @Test void missionOutsideTodaysRotationCannotBeClaimed() {
        MarketService market = market();
        List<String> today = MarketService.dailyMissionIds(LocalDate.now(ZoneId.of("Asia/Seoul")));
        String notAssigned = MarketService.MISSION_IDS.stream()
                .filter(id -> !today.contains(id)).findFirst().orElseThrow();

        var error = assertThrows(IllegalArgumentException.class, () -> market.rewardMission(notAssigned, 7L));
        assertTrue(error.getMessage().contains("오늘의 미션"));
        assertEquals(1_000_000L, cash.get());
    }

    @Test void watchMissionRequiresAWatchlistEntry() {
        MarketService market = market();
        LocalDate day = LocalDate.of(2026, 10, 1);
        while (!MarketService.dailyMissionIds(day).contains("watch")) day = day.plusDays(1);
        ReflectionTestUtils.setField(market, "clock", Clock.fixed(
                day.atTime(12, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC));
        var error = assertThrows(IllegalArgumentException.class, () -> market.rewardMission("watch", 7L));
        assertTrue(error.getMessage().contains("관심종목"));
        assertEquals(1_000_000L, cash.get());
        assertTrue(claims.isEmpty(), "unmet condition must not consume the daily claim");

        watchlistCount.set(1);
        var result = market.rewardMission("watch", 7L);
        assertTrue(result.awarded());
        assertEquals(10_000L, wallet.get());
        assertEquals(1_000_000L, cash.get());
    }
}
