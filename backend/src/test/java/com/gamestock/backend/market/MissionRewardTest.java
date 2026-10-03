package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        for (String id : List.of("market", "news", "watch")) {
            var first = market.rewardMission(id, 7L);
            assertTrue(first.awarded());
            assertEquals(50_000L, first.rewardCash());
            var second = market.rewardMission(id, 7L);
            assertFalse(second.awarded());
            assertEquals(0L, second.rewardCash());
        }
        assertEquals(1_150_000L, cash.get());
        assertThrows(IllegalArgumentException.class, () -> market.rewardMission("unknown", 7L));
        assertEquals(1_150_000L, cash.get());
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
