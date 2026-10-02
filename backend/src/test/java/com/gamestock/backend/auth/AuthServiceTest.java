package com.gamestock.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");

    private AuthService service(JdbcTemplate jdbc, Instant changedAt) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("nickname")).thenReturn("원래닉네임");
        when(rs.getTimestamp("nickname_changed_at")).thenReturn(changedAt == null ? null : Timestamp.from(changedAt));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                ((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
        return new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void firstChangeIsAvailable() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, null);
        assertTrue(auth.profile(7L).nicknameChangeAvailable());
        assertNull(auth.profile(7L).nicknameChangeAvailableAt());
        auth.updateProfile(7L, new AuthService.ProfileUpdate("새닉네임", ""));
        verify(jdbc).update(contains("nickname_changed_at = ?"), eq("새닉네임"), eq(""), eq(Timestamp.from(NOW)), eq(7L));
    }

    @Test void changeIsBlockedUntilSeventyTwoHoursHaveElapsed() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, NOW.minus(Duration.ofDays(3)).plusSeconds(1));
        assertFalse(auth.profile(7L).nicknameChangeAvailable());
        assertEquals(NOW.plusSeconds(1).toString(), auth.profile(7L).nicknameChangeAvailableAt());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> auth.updateProfile(7L, new AuthService.ProfileUpdate("새닉네임", "")));
        assertEquals(409, error.getStatusCode().value());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void changeIsAllowedAtExactlySeventyTwoHours() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, NOW.minus(Duration.ofDays(3)));
        assertTrue(auth.profile(7L).nicknameChangeAvailable());
        auth.updateProfile(7L, new AuthService.ProfileUpdate("새닉네임", ""));
        verify(jdbc).update(contains("nickname_changed_at = ?"), eq("새닉네임"), eq(""), eq(Timestamp.from(NOW)), eq(7L));
    }

    @Test void unchangedNicknameDoesNotRestartCooldown() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, NOW.minusSeconds(30));
        auth.updateProfile(7L, new AuthService.ProfileUpdate("원래닉네임", "picture"));
        verify(jdbc).update("UPDATE users SET profile_image_url = ?, profile_completed = TRUE WHERE id = ?", "picture", 7L);
    }
}