package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionRewardTest {
    private final Set<String> claims = new HashSet<>();
    private final AtomicLong cash = new AtomicLong(1_000_000L);
    private final AtomicLong watchlistCount = new AtomicLong();

    private MarketService market() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class, call -> {
            String method = call.getMethod().getName();
            Object[] args = call.getArguments();
            String sql = args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
            if (method.equals("update")) {
                if (sql.startsWith("INSERT IGNORE INTO mission_rewards")) {
                    assertEquals(50_000L, args[4]);
                    assertTrue(sql.contains("rewarded_on"));
                    return claims.add(args[2] + "/" + args[3]) ? 1 : 0;
                }
                if (sql.startsWith("UPDATE users SET cash")) cash.addAndGet((Long) args[1]);
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

    @Test void eachMissionPaysFiftyThousandOnlyOncePerDay() {
        watchlistCount.set(1);
        MarketService market = market();
        List<String> today = MarketService.dailyMissionIds(LocalDate.now(ZoneId.of("Asia/Seoul")));
        assertEquals(5, today.size());
        for (String id : today) {
            var first = market.rewardMission(id, 7L);
            assertTrue(first.awarded());
            assertEquals(50_000L, first.rewardCash());
            var second = market.rewardMission(id, 7L);
            assertFalse(second.awarded());
            assertEquals(0L, second.rewardCash());
        }
        assertEquals(1_250_000L, cash.get());
        assertThrows(IllegalArgumentException.class, () -> market.rewardMission("unknown", 7L));
        assertEquals(1_250_000L, cash.get());
    }

    @Test void rotatingMissionSetChangesByKoreanDay() {
        List<String> today = MarketService.dailyMissionIds(LocalDate.of(2026, 10, 8));
        List<String> tomorrow = MarketService.dailyMissionIds(LocalDate.of(2026, 10, 9));

        assertEquals(5, today.size());
        assertEquals(5, new HashSet<>(today).size());
        assertNotEquals(today, tomorrow);
        assertEquals(List.of("market", "news", "watch"), today.subList(0, 3));
        assertEquals(List.of("market", "news", "watch"), tomorrow.subList(0, 3));
    }

    @Test void missionOutsideTodaysRotationCannotBeClaimed() {
        MarketService market = market();
        List<String> today = MarketService.dailyMissionIds(LocalDate.now(ZoneId.of("Asia/Seoul")));
        String notAssigned = List.of("ranking", "portfolio", "home", "settings").stream()
                .filter(id -> !today.contains(id)).findFirst().orElseThrow();

        var error = assertThrows(IllegalArgumentException.class, () -> market.rewardMission(notAssigned, 7L));
        assertTrue(error.getMessage().contains("오늘의 미션"));
        assertEquals(1_000_000L, cash.get());
    }

    @Test void watchMissionRequiresAWatchlistEntry() {
        MarketService market = market();
        var error = assertThrows(IllegalArgumentException.class, () -> market.rewardMission("watch", 7L));
        assertTrue(error.getMessage().contains("관심종목"));
        assertEquals(1_000_000L, cash.get());
        assertTrue(claims.isEmpty(), "unmet condition must not consume the daily claim");

        watchlistCount.set(1);
        var result = market.rewardMission("watch", 7L);
        assertTrue(result.awarded());
        assertEquals(1_050_000L, cash.get());
    }
}
