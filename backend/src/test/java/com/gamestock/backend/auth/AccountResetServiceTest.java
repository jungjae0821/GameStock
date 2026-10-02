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
        var result = new AccountResetService(jdbc).reset(7L);
        assertNotNull(result.resetAt());
        var order = inOrder(jdbc);
        order.verify(jdbc).update("DELETE FROM portfolios WHERE user_id = ?", 7L);
        order.verify(jdbc).update("DELETE FROM attendance_rewards WHERE user_id = ?", 7L);
        order.verify(jdbc).update("DELETE FROM mission_rewards WHERE user_id = ?", 7L);
        verify(jdbc).update(contains("reset_used_at = CURRENT_TIMESTAMP"), eq(1_000_000L), eq(7L));
    }

    @Test void alreadyUsedResetDoesNotDeleteMissions() throws Exception {
        JdbcTemplate jdbc = database(Timestamp.from(Instant.parse("2026-10-01T03:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> new AccountResetService(jdbc).reset(7L));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}