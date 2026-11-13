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

    @Test void newNicknameMustBeTwoToTenCharacters() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, null);
        assertThrows(IllegalArgumentException.class, () -> auth.updateProfile(7L, new AuthService.ProfileUpdate("가", "")));
        assertThrows(IllegalArgumentException.class, () -> auth.updateProfile(7L, new AuthService.ProfileUpdate("열한글자닉네임입니다요", "")));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        auth.updateProfile(7L, new AuthService.ProfileUpdate("열글자닉네임입니다", ""));
        verify(jdbc).update(contains("nickname_changed_at = ?"), eq("열글자닉네임입니다"), eq(""), eq(Timestamp.from(NOW)), eq(7L));
    }

    @Test void unchangedNicknameDoesNotRestartCooldown() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, NOW.minusSeconds(30));
        auth.updateProfile(7L, new AuthService.ProfileUpdate("원래닉네임", "/profile-avatars/mint-hood-384.png"));
        verify(jdbc).update("UPDATE users SET profile_image_url = ?, profile_completed = TRUE WHERE id = ?", "/profile-avatars/mint-hood-384.png", 7L);
    }

    private static final AuthService.LoginUser USER = new AuthService.LoginUser(7L, "닉네임", "", "", 0, 0, false);

    @Test void attendanceUsesKoreanDateAfterUtcDayBoundary() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("INSERT IGNORE INTO attendance_rewards"), any(Object[].class))).thenReturn(1);
        // 2026-10-02 15:30 UTC is already 2026-10-03 00:30 in Seoul.
        AuthService auth = new AuthService(jdbc, Clock.fixed(Instant.parse("2026-10-02T15:30:00Z"), ZoneOffset.UTC));

        var user = auth.grantAttendanceReward(USER);

        assertEquals(10_000L, user.attendanceReward());
        verify(jdbc).update(startsWith("INSERT IGNORE INTO attendance_rewards"), eq(7L), eq(java.time.LocalDate.parse("2026-10-03")), eq(1), eq(10_000L));
        // The reward goes to the personal wallet, never the trading account.
        verify(jdbc).update(contains("INSERT IGNORE INTO personal_ledger"), eq("attendance:7:2026-10-03"), eq(7L), eq("ATTENDANCE"), eq(10_000L), anyString());
        verify(jdbc, never()).update(eq("UPDATE users SET cash = cash + ? WHERE id = ?"), any(Object[].class));
    }

    @Test void concurrentDuplicateAttendanceDoesNotPayTwiceOrFail() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Another request inserted today's row after this one checked; INSERT IGNORE reports 0 rows.
        when(jdbc.update(startsWith("INSERT IGNORE INTO attendance_rewards"), any(Object[].class))).thenReturn(0);
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        var user = assertDoesNotThrow(() -> auth.grantAttendanceReward(USER));

        assertEquals(0L, user.attendanceReward());
        verify(jdbc, never()).update(contains("INSERT IGNORE INTO personal_ledger"), any(Object[].class));
    }

    @Test void attendanceKeepsYesterdaysStreakUntilTodayIsChecked() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var yesterday = new AuthService.AttendanceDay("2026-10-01", 6, 500_000L);
        doReturn(java.util.List.of(yesterday)).when(jdbc).query(contains("BETWEEN"), any(RowMapper.class), any(Object[].class));
        doReturn(java.util.List.of(yesterday)).when(jdbc).query(contains("LIMIT 1"), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(java.util.Map.of("days", 9L, "reward", 3_000_000L, "longest", 6));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        var attendance = auth.attendance(7L, null);

        assertEquals("2026-10", attendance.month());
        assertFalse(attendance.checkedToday());
        assertEquals(6, attendance.streak());
        assertEquals(50_000L, attendance.nextReward());
        assertEquals(9, attendance.totalDays());
        verify(jdbc).query(contains("BETWEEN"), any(RowMapper.class), eq(7L),
                eq(java.time.LocalDate.parse("2026-10-01")), eq(java.time.LocalDate.parse("2026-10-31")));
    }

    @Test void attendanceStreakIsBrokenAfterAMissedDay() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(java.util.List.of(new AuthService.AttendanceDay("2026-09-29", 3, 300_000L)))
                .when(jdbc).query(contains("LIMIT 1"), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(java.util.Map.of("days", 3L, "reward", 600_000L, "longest", 3));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        var attendance = auth.attendance(7L, "2026-09");

        assertEquals(0, attendance.streak());
        assertEquals(10_000L, attendance.nextReward());
        var error = assertThrows(ResponseStatusException.class, () -> auth.attendance(7L, "2026-9"));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test void databaseFailureIsNotReportedAsExpiredLogin() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        com.google.firebase.auth.FirebaseAuth firebase = mock(com.google.firebase.auth.FirebaseAuth.class);
        com.google.firebase.auth.FirebaseToken token = mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getUid()).thenReturn("uid-7");
        when(firebase.verifyIdToken("valid", true)).thenReturn(token);

        try (var statics = mockStatic(com.google.firebase.auth.FirebaseAuth.class)) {
            statics.when(com.google.firebase.auth.FirebaseAuth::getInstance).thenReturn(firebase);
            assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                    () -> auth.requireUser("Bearer valid"));
        }
    }

    @Test void invalidTokenIsStillUnauthorized() throws Exception {
        AuthService auth = new AuthService(mock(JdbcTemplate.class), Clock.fixed(NOW, ZoneOffset.UTC));
        com.google.firebase.auth.FirebaseAuth firebase = mock(com.google.firebase.auth.FirebaseAuth.class);
        when(firebase.verifyIdToken("bad", true)).thenThrow(new IllegalArgumentException("bad token"));

        try (var statics = mockStatic(com.google.firebase.auth.FirebaseAuth.class)) {
            statics.when(com.google.firebase.auth.FirebaseAuth::getInstance).thenReturn(firebase);
            var error = assertThrows(ResponseStatusException.class, () -> auth.requireUser("Bearer bad"));
            assertEquals(401, error.getStatusCode().value());
        }
    }

    @Test void revokedTokenCannotMintANewMobileSession() throws Exception {
        var firebase=mock(com.google.firebase.auth.FirebaseAuth.class);
        when(firebase.verifyIdToken("revoked",true)).thenThrow(new IllegalArgumentException("revoked"));
        try(var mocked=mockStatic(com.google.firebase.auth.FirebaseAuth.class)) {
            mocked.when(com.google.firebase.auth.FirebaseAuth::getInstance).thenReturn(firebase);
            var auth=new AuthService(mock(JdbcTemplate.class),Clock.fixed(NOW,ZoneOffset.UTC));
            assertThrows(ResponseStatusException.class,()->auth.issueMobileCode("Bearer revoked"));
            verify(firebase,never()).createCustomToken(anyString());
        }
    }

    @Test void exhaustedShortNicknamesHaveAnExpandableFallback() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(1);
        var auth=new AuthService(jdbc,Clock.fixed(NOW,ZoneOffset.UTC));
        String name=org.springframework.test.util.ReflectionTestUtils.invokeMethod(auth,"defaultNickname");
        assertNotNull(name);
        assertTrue(name.startsWith("슈엔단"));
        assertTrue(name.length()>7 && name.length()<=50);
    }

    @Test void nicknameOnlyUpdatePreservesExistingProfileImage() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("nickname")).thenReturn("원래닉네임");
        when(rs.getString("profile_image_url")).thenReturn("https://lh3.googleusercontent.com/a/provider-photo");
        when(rs.getTimestamp("nickname_changed_at")).thenReturn(null);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                ((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        auth.updateProfile(7L, new AuthService.ProfileUpdate("원래닉네임", null));

        verify(jdbc).update("UPDATE users SET profile_image_url = ?, profile_completed = TRUE WHERE id = ?", "https://lh3.googleusercontent.com/a/provider-photo", 7L);
    }

    @Test void externalProfileImageIsRejected() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, null);

        var error = assertThrows(IllegalArgumentException.class,
                () -> auth.updateProfile(7L, new AuthService.ProfileUpdate("원래닉네임", "https://tracker.example/pixel.png")));

        assertTrue(error.getMessage().contains("Google"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void ownUploadedPhotoCanBeSelectedAgain() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, null);
        String photo = "/api/profile-photos/0123456789abcdef0123456789abcdef.jpg";
        when(jdbc.queryForObject(contains("FROM profile_photos"), eq(Integer.class), eq("0123456789abcdef0123456789abcdef"), eq(7L))).thenReturn(1);

        auth.updateProfile(7L, new AuthService.ProfileUpdate("원래닉네임", photo));

        verify(jdbc).update("UPDATE users SET profile_image_url = ?, profile_completed = TRUE WHERE id = ?", photo, 7L);
    }

    @Test void someoneElsesUploadedPhotoIsRejected() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, null);
        when(jdbc.queryForObject(contains("FROM profile_photos"), eq(Integer.class), anyString(), eq(7L))).thenReturn(0);

        var error = assertThrows(IllegalArgumentException.class, () -> auth.updateProfile(7L,
                new AuthService.ProfileUpdate("원래닉네임", "/api/profile-photos/0123456789abcdef0123456789abcdef.jpg")));

        assertTrue(error.getMessage().contains("직접 올린"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void legacyExternalImageIsDroppedOnNicknameOnlyUpdate() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("nickname")).thenReturn("원래닉네임");
        when(rs.getString("profile_image_url")).thenReturn("https://tracker.example/pixel.png");
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                ((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        auth.updateProfile(7L, new AuthService.ProfileUpdate("원래닉네임", null));

        verify(jdbc).update("UPDATE users SET profile_image_url = ?, profile_completed = TRUE WHERE id = ?", "", 7L);
    }

    @Test void activeBanSuspendsTheAccountAndShowsOnProfile() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("nickname")).thenReturn("원래닉네임");
        when(rs.getTimestamp("trade_banned_until")).thenReturn(Timestamp.from(NOW.plus(Duration.ofDays(2))));
        when(rs.getTimestamp(1)).thenReturn(Timestamp.from(NOW.plus(Duration.ofDays(2))));
        when(rs.getString(2)).thenReturn("부계정 의심");
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                ((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
        when(jdbc.query(startsWith("SELECT trade_banned_until"), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                java.util.List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0)));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(NOW.plus(Duration.ofDays(2)).toString(), auth.profile(7L).tradeBannedUntil());
        var error = assertThrows(ResponseStatusException.class, () -> auth.requireNotSuspended(7L));
        assertEquals(403, error.getStatusCode().value());
        assertTrue(error.getReason().contains("부계정 의심"));
        assertTrue(error.getReason().contains("이용이 정지된"));
    }

    @Test void expiredBanAllowsTheAccount() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp(1)).thenReturn(Timestamp.from(NOW.minusSeconds(1)));
        when(jdbc.query(startsWith("SELECT trade_banned_until"), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                java.util.List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0)));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        assertDoesNotThrow(() -> auth.requireNotSuspended(7L));
    }

    /** Signed-in account fixture: Firebase uid "uid-7" maps to user 7 with the given role and ban. */
    private interface SignedIn { void run(AuthService auth) throws Exception; }

    private void signedIn(JdbcTemplate jdbc, String role, Instant bannedUntil, String email, SignedIn body) throws Exception {
        ResultSet user = mock(ResultSet.class);
        when(user.getLong("id")).thenReturn(7L);
        when(user.getString("nickname")).thenReturn("닉네임");
        when(user.getBoolean("profile_completed")).thenReturn(true);
        when(user.getString("role")).thenReturn(role);
        when(jdbc.query(contains("WHERE google_uid = ?"), any(RowMapper.class), eq("uid-7"))).thenAnswer(call ->
                java.util.List.of(((RowMapper<?>) call.getArgument(1)).mapRow(user, 0)));
        ResultSet ban = mock(ResultSet.class);
        when(ban.getTimestamp(1)).thenReturn(bannedUntil == null ? null : Timestamp.from(bannedUntil));
        when(jdbc.query(startsWith("SELECT trade_banned_until"), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                java.util.List.of(((RowMapper<?>) call.getArgument(1)).mapRow(ban, 0)));
        when(jdbc.update(startsWith("INSERT IGNORE INTO attendance_rewards"), any(Object[].class))).thenReturn(1);
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        com.google.firebase.auth.FirebaseAuth firebase = mock(com.google.firebase.auth.FirebaseAuth.class);
        com.google.firebase.auth.FirebaseToken token = mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getUid()).thenReturn("uid-7");
        when(token.getEmail()).thenReturn(email);
        when(token.isEmailVerified()).thenReturn(true);
        when(firebase.verifyIdToken("valid", true)).thenReturn(token);
        try (var statics = mockStatic(com.google.firebase.auth.FirebaseAuth.class)) {
            statics.when(com.google.firebase.auth.FirebaseAuth::getInstance).thenReturn(firebase);
            body.run(auth);
        }
    }

    @Test void suspendedAccountIsRefusedEverywhereButLoginAndPaysNoAttendance() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        signedIn(jdbc, "USER", NOW.plus(Duration.ofDays(1)), "user@example.com", auth -> {
            var error = assertThrows(ResponseStatusException.class, () -> auth.requireUser("Bearer valid"));
            assertEquals(403, error.getStatusCode().value());
            // 로그인과 프로필 조회는 정지 안내를 띄우기 위해 열어 둔다.
            assertEquals(7L, auth.requireUserAllowingSuspended("Bearer valid").id());
            assertEquals(7L, auth.login("Bearer valid").id());
        });
        verify(jdbc, never()).update(startsWith("INSERT IGNORE INTO attendance_rewards"), any(Object[].class));
    }

    @Test void adminRemovedFromSettingsLosesTheRole() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        signedIn(jdbc, "ADMIN", null, "old-admin@example.com", auth -> {
            org.springframework.test.util.ReflectionTestUtils.setField(auth, "adminGoogleEmail", "admin@example.com");
            var user = auth.requireUser("Bearer valid");
            assertEquals("USER", user.role());
            assertThrows(ResponseStatusException.class, () -> auth.requireAdmin("Bearer valid"));
        });
        verify(jdbc, atLeastOnce()).update("UPDATE users SET role = 'USER' WHERE id = ? AND role = 'ADMIN'", 7L);
    }

    @Test void configuredAdminKeepsTheRole() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        signedIn(jdbc, "ADMIN", null, "admin@example.com", auth -> {
            org.springframework.test.util.ReflectionTestUtils.setField(auth, "adminGoogleEmail", "admin@example.com");
            assertEquals("ADMIN", auth.requireAdmin("Bearer valid").role());
        });
        verify(jdbc, never()).update(contains("role = 'USER'"), any(Object[].class));
    }

    @Test void emptyAdminSettingsDoNotRevokeExistingAdmins() throws Exception {
        // 배포 환경변수가 빠졌을 때 관리자를 한꺼번에 잃지 않도록 한다.
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        signedIn(jdbc, "ADMIN", null, "admin@example.com", auth ->
                assertEquals("ADMIN", auth.requireAdmin("Bearer valid").role()));
        verify(jdbc, never()).update(contains("role = 'USER'"), any(Object[].class));
    }

    @Test void signupThatCannotCreateTheAccountAsksToRetryInsteadOf500() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 계정 조회는 늘 비어 있고, INSERT는 자동 닉네임이 겹쳐 계속 실패한다.
        when(jdbc.query(contains("WHERE google_uid = ?"), any(RowMapper.class), eq("uid-7"))).thenReturn(java.util.List.of());
        when(jdbc.update(startsWith("INSERT INTO users"), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("nickname"));
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM users WHERE nickname"), eq(Integer.class), any(Object[].class))).thenReturn(1);
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        com.google.firebase.auth.FirebaseAuth firebase = mock(com.google.firebase.auth.FirebaseAuth.class);
        com.google.firebase.auth.FirebaseToken token = mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getUid()).thenReturn("uid-7");
        when(firebase.verifyIdToken("valid", true)).thenReturn(token);
        try (var statics = mockStatic(com.google.firebase.auth.FirebaseAuth.class)) {
            statics.when(com.google.firebase.auth.FirebaseAuth::getInstance).thenReturn(firebase);
            var error = assertThrows(ResponseStatusException.class, () -> auth.login("Bearer valid"));
            assertEquals(503, error.getStatusCode().value());
        }
    }
}
