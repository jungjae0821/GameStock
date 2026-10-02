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
    @Test void eachMissionPaysFiftyThousandOnlyOncePerDay() {
        Set<String> claims = new HashSet<>();
        AtomicLong cash = new AtomicLong(1_000_000L);
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
            if (method.equals("queryForObject")) return sql.startsWith("SELECT cash") ? cash.get() : 0L;
            if (method.equals("query") || method.equals("queryForList")) return List.of();
            return RETURNS_DEFAULTS.answer(call);
        });
        MarketService market = new MarketService(mock(ApplicationEventPublisher.class), jdbc,
                mock(UserFeatureService.class), mock(TradingProtectionService.class));
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
}