package com.gamestock.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountResetServiceTest {
    private final AuthService auth = mock(AuthService.class);

    private JdbcTemplate database(Timestamp usedAt) throws Exception {
        return mock(JdbcTemplate.class, call -> {
            String method = call.getMethod().getName();
            Object[] args = call.getArguments();
            String sql = args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
            if (method.equals("query") && sql.contains("SELECT reset_used_at")) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getTimestamp("reset_used_at")).thenReturn(usedAt);
                return List.of(((RowMapper<?>) args[1]).mapRow(rs, 0));
            }
            if (method.equals("query")) return List.of();
            if (method.equals("queryForObject")) {
                return sql.contains("market_locks") ? 1 : Timestamp.from(Instant.parse("2026-10-02T03:00:00Z"));
            }
            if (method.equals("update")) return 1;
            return RETURNS_DEFAULTS.answer(call);
        });
    }

    @Test void clearsOnlyTheResetUsersMissionsTogetherWithPortfolio() throws Exception {
        JdbcTemplate jdbc = database(null);
        when(auth.generateNickname()).thenReturn("슈엔단1234");
        var result = new AccountResetService(jdbc, auth).reset(7L);
        assertNotNull(result.resetAt());
        var order = inOrder(jdbc);
        order.verify(jdbc).update("DELETE FROM portfolios WHERE user_id = ?", 7L);
        order.verify(jdbc).update("DELETE FROM attendance_rewards WHERE user_id = ?", 7L);
        order.verify(jdbc).update("DELETE FROM mission_rewards WHERE user_id = ?", 7L);
        verify(jdbc).update(contains("reset_used_at = CURRENT_TIMESTAMP"), eq(1_000_000L), eq("슈엔단1234"), eq(7L));
    }

    @Test void resetInvalidatesTheMatchingCacheAndClearsTheNicknameCooldown() throws Exception {
        JdbcTemplate jdbc = database(null);
        when(auth.generateNickname()).thenReturn("슈엔단5678");
        new AccountResetService(jdbc, auth).reset(7L);
        var order = inOrder(jdbc);
        order.verify(jdbc).queryForObject(contains("market_locks"), eq(Integer.class));
        // 봇 매칭 캐시가 취소된 주문과 지운 보유 수량을 다시 쓰지 않도록 같은 트랜잭션에서 버전을 올린다.
        order.verify(jdbc).update("UPDATE market_book_revisions SET revision=revision+1");
        order.verify(jdbc).update(contains("DELETE FROM portfolios"), eq(7L));
        verify(jdbc).update(contains("nickname_changed_at = NULL"), eq(1_000_000L), eq("슈엔단5678"), eq(7L));
    }

    @Test void alreadyUsedResetDoesNotDeleteMissions() throws Exception {
        JdbcTemplate jdbc = database(Timestamp.from(Instant.parse("2026-10-01T03:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> new AccountResetService(jdbc, auth).reset(7L));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void resetUsesTheDefaultNicknameFormat() throws Exception {
        JdbcTemplate jdbc = database(null);
        when(auth.generateNickname()).thenReturn("슈엔단0420");

        new AccountResetService(jdbc, auth).reset(7L);

        verify(jdbc).update(contains("nickname = ?"), eq(1_000_000L), eq("슈엔단0420"), eq(7L));
    }

    @Test void resetPausesStrategiesBeforeClearingBalances() throws Exception {
        JdbcTemplate jdbc = database(null);
        new AccountResetService(jdbc, auth).reset(7L);
        var order = inOrder(jdbc);
        order.verify(jdbc).update(contains("UPDATE quant_projects SET status='PAUSED'"), eq(7L));
        order.verify(jdbc).update("DELETE FROM portfolios WHERE user_id = ?", 7L);
    }

    @Test void resetRejectsUnfinishedStrategyOrder() throws Exception {
        JdbcTemplate jdbc = database(null);
        doReturn(List.of("RUNNING")).when(jdbc).query(contains("SELECT status FROM quant_projects"), any(RowMapper.class), eq(7L));
        assertThrows(IllegalArgumentException.class, () -> new AccountResetService(jdbc, auth).reset(7L));
        verify(jdbc, never()).update("DELETE FROM portfolios WHERE user_id = ?", 7L);
    }
}
