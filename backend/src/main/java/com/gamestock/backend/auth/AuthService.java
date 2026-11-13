package com.gamestock.backend.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDate;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AuthService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);
    private static final long MOBILE_CODE_TTL_SECONDS = 120;
    /** Attendance resets at Korean midnight, the same boundary as daily missions. */
    private static final ZoneId ATTENDANCE_ZONE = ZoneId.of("Asia/Seoul");
    /** Attendance pays this much per consecutive day, up to five days, into the personal wallet. */
    static final long ATTENDANCE_STEP = 10_000L;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    /**
     * The administrator is still authenticated by Firebase. These optional
     * allowlists only decide which verified Firebase identity receives ADMIN on
     * first login; they never create an unauthenticated bypass.
     */
    @Value("${gamestock.auth.admin-google-uid:}")
    private String adminGoogleUid;
    @Value("${gamestock.auth.admin-google-email:}")
    private String adminGoogleEmail;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }

    AuthService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @jakarta.annotation.PostConstruct
    public void initializeMobileCodes() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mobile_login_codes (
                    code_hash CHAR(64) PRIMARY KEY,
                    custom_token TEXT NOT NULL,
                    expires_at TIMESTAMP(3) NOT NULL,
                    INDEX ix_mobile_code_expiry (expires_at)
                )
                """);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void removeExpiredMobileCodes() {
        jdbc.update("DELETE FROM mobile_login_codes WHERE expires_at <= ?", Timestamp.from(clock.instant()));
    }

    private static String codeHash(String code) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Only token verification maps to 401. Database failures while loading the
     * account propagate as server errors instead of looking like an expired login.
     * 이용 정지된 계정은 정지가 풀릴 때까지 모든 개인 기능에서 403을 받는다.
     */
    @Transactional
    public LoginUser requireUser(String authorization) {
        LoginUser user = findOrCreate(verifyToken(authorization));
        requireNotSuspended(user.id());
        return grantAttendanceReward(user);
    }

    /**
     * 로그인과 프로필 조회만 정지된 계정에도 열어 둔다. 화면이 정지 기간과 사유를
     * 받아 안내해야 하기 때문이다. 정지 중에는 출석 보상을 주지 않는다.
     */
    @Transactional
    public LoginUser requireUserAllowingSuspended(String authorization) {
        LoginUser user = findOrCreate(verifyToken(authorization));
        return suspension(user.id()) == null ? grantAttendanceReward(user) : user;
    }

    @Transactional
    public LoginUser login(String authorization) { return requireUserAllowingSuspended(authorization); }

    public MobileCode issueMobileCode(String authorization) {
        FirebaseToken token = verifyToken(authorization);
        try {
            String customToken = FirebaseAuth.getInstance().createCustomToken(token.getUid());
            String code = UUID.randomUUID().toString().replace("-", "");
            Instant expiresAt = clock.instant().plusSeconds(MOBILE_CODE_TTL_SECONDS);
            jdbc.update("DELETE FROM mobile_login_codes WHERE expires_at <= ?", Timestamp.from(clock.instant()));
            jdbc.update("INSERT INTO mobile_login_codes (code_hash, custom_token, expires_at) VALUES (?, ?, ?)",
                    codeHash(code), customToken, Timestamp.from(expiresAt));
            return new MobileCode(code, expiresAt.toString());
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "모바일 로그인 코드를 만들지 못했습니다.");
        }
    }

    @Transactional
    public MobileToken exchangeMobileCode(MobileCodeExchange request) {
        if (request == null || request.code() == null || request.code().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "로그인 인증 코드가 필요합니다.");
        String code = request.code().trim();
        if (!code.matches("[a-f0-9]{32}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 로그인 코드입니다.");
        String hash = codeHash(code);
        PendingMobileCode pending = jdbc.query(
                "SELECT custom_token, expires_at FROM mobile_login_codes WHERE code_hash = ? FOR UPDATE",
                (rs, row) -> new PendingMobileCode(rs.getString("custom_token"), rs.getTimestamp("expires_at").toInstant()), hash)
                .stream().findFirst().orElse(null);
        if (pending == null || !pending.expiresAt().isAfter(clock.instant()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "만료되었거나 이미 사용한 로그인 코드입니다.");
        jdbc.update("DELETE FROM mobile_login_codes WHERE code_hash = ?", hash);
        return new MobileToken(pending.customToken());
    }

    @Transactional
    public LoginUser requireAdmin(String authorization) {
        LoginUser user = requireUser(authorization);
        if (!"ADMIN".equalsIgnoreCase(user.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        return user;
    }

    private FirebaseToken verifyToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        try {
            return FirebaseAuth.getInstance().verifyIdToken(authorization.substring(7), true);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 로그인입니다.");
        }
    }

    public Profile profile(long userId) {
        return readProfile(userId, false);
    }

    private Profile readProfile(long userId, boolean lock) {
        return jdbc.queryForObject("SELECT nickname, email, profile_image_url, profile_completed, reset_used_at, nickname_changed_at, role, trade_banned_until, trade_ban_reason FROM users WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> {
                    Timestamp changedAt = rs.getTimestamp("nickname_changed_at");
                    Instant nextChange = changedAt == null ? null : changedAt.toInstant().plus(Duration.ofDays(3));
                    Instant bannedUntil = activeBan(rs.getTimestamp("trade_banned_until"));
                    return new Profile(rs.getString("nickname"), rs.getString("email"),
                            rs.getString("profile_image_url"), rs.getBoolean("profile_completed"),
                            rs.getTimestamp("reset_used_at") == null,
                            nextChange == null || !clock.instant().isBefore(nextChange),
                            nextChange == null ? null : nextChange.toString(),
                            "ADMIN".equalsIgnoreCase(rs.getString("role")),
                            bannedUntil == null ? null : bannedUntil.toString(),
                            bannedUntil == null ? null : rs.getString("trade_ban_reason"));
                }, userId);
    }

    /** 관리자가 건 거래 제한(이용 정지)이 남아 있으면 어떤 개인 기능도 쓸 수 없다. */
    public void requireNotSuspended(long userId) {
        Object[] ban = suspension(userId);
        if (ban == null) return;
        String until = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(((Instant) ban[0]).atZone(ATTENDANCE_ZONE));
        String reason = (String) ban[1];
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "이용이 정지된 계정입니다. " + until + "까지 서비스를 이용할 수 없습니다." + (reason == null || reason.isBlank() ? "" : " (사유: " + reason + ")"));
    }

    /** 남은 정지의 {종료 시각, 사유}. 정지가 없거나 끝났으면 null. */
    private Object[] suspension(long userId) {
        var rows = jdbc.query("SELECT trade_banned_until, trade_ban_reason FROM users WHERE id = ?",
                (rs, row) -> new Object[] { activeBan(rs.getTimestamp(1)), rs.getString(2) }, userId);
        return rows.isEmpty() || rows.get(0)[0] == null ? null : rows.get(0);
    }

    private Instant activeBan(Timestamp until) {
        return until != null && clock.instant().isBefore(until.toInstant()) ? until.toInstant() : null;
    }

    static final int NICKNAME_MIN_LENGTH = 2;
    static final int NICKNAME_MAX_LENGTH = 10;

    @Transactional
    public Profile updateProfile(long userId, ProfileUpdate update) {
        String nickname = update.nickname() == null ? "" : update.nickname().trim();
        // Serialize concurrent profile changes on the user's row.
        Profile current = readProfile(userId, true);
        boolean changed = !nickname.equals(current.nickname());
        // Nicknames saved before the 10-character limit stay valid until the user changes them.
        if (changed && (nickname.length() < NICKNAME_MIN_LENGTH || nickname.length() > NICKNAME_MAX_LENGTH))
            throw new IllegalArgumentException("닉네임은 2~10자로 입력해 주세요.");
        // Nickname-only updates from older clients must not erase an existing
        // selected avatar or the Firebase provider photo. A legacy external URL
        // stored before validation existed is dropped instead of blocking the update.
        String image = update.profileImageUrl() == null
                ? ProfileImages.sanitize(current.profileImageUrl())
                : update.profileImageUrl().trim();
        if (image.length() > 500) throw new IllegalArgumentException("프로필 이미지 주소가 너무 깁니다.");
        if (!ProfileImages.allowed(image))
            throw new IllegalArgumentException("제공된 캐릭터 이미지, 직접 올린 사진, Google 프로필 사진만 사용할 수 있습니다.");
        // 다른 사람이 올린 사진 주소를 자기 프로필로 가져가지 못하게 한다.
        String photoId = ProfileImages.uploadedPhotoId(image);
        if (photoId != null && !image.equals(current.profileImageUrl()) && !ownsPhoto(userId, photoId))
            throw new IllegalArgumentException("직접 올린 사진만 사용할 수 있습니다.");
        if (changed && !current.nicknameChangeAvailable())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "닉네임은 3일에 한 번 변경할 수 있습니다.");
        try {
            if (changed) {
                jdbc.update("UPDATE users SET nickname = ?, profile_image_url = ?, profile_completed = TRUE, nickname_changed_at = ? WHERE id = ?",
                        nickname, image, Timestamp.from(clock.instant()), userId);
            } else {
                jdbc.update("UPDATE users SET profile_image_url = ?, profile_completed = TRUE WHERE id = ?", image, userId);
            }
        } catch (DuplicateKeyException error) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다.");
        }
        return profile(userId);
    }
    private boolean ownsPhoto(long userId, String photoId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM profile_photos WHERE id = ? AND user_id = ?", Integer.class, photoId, userId);
        return count != null && count > 0;
    }

    private LoginUser findOrCreate(FirebaseToken token) {
        // The existing google_uid column stores the Firebase UID for both providers.
        // Keep that key so existing Google accounts retain their assets and history.
        var existing = jdbc.query("SELECT id, nickname, email, profile_image_url, profile_completed, role FROM users WHERE google_uid = ?",
                (rs, row) -> new LoginUser(rs.getLong("id"), rs.getString("nickname"), rs.getString("email"), rs.getString("profile_image_url"), 0, 0, !rs.getBoolean("profile_completed"), rs.getString("role")), token.getUid());
        LoginUser user;
        if (!existing.isEmpty()) user = existing.get(0);
        else {
            String email = token.getEmail() == null ? "" : token.getEmail();
            String picture = ProfileImages.sanitize(token.getPicture());
            String username = "firebase_" + token.getUid();
            String nickname = defaultNickname();
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    // 신규 계정은 자동 닉네임으로 바로 생성한다. 사용자는 이후
                    // 마이페이지에서 닉네임을 변경할 수 있다. 변경 후에는 3일간 기다린다.
                    jdbc.update("INSERT INTO users (username, password_hash, nickname, google_uid, email, profile_image_url, profile_completed, cash) VALUES (?, 'FIREBASE', ?, ?, ?, ?, TRUE, 1000000)",
                            username, nickname, token.getUid(), email, picture);
                    break;
                } catch (DuplicateKeyException error) {
                    // 닉네임 4자리 숫자가 동시에 겹친 경우에만 새 값을 뽑아
                    // 재시도한다. 같은 Firebase UID가 먼저 생성된 경우는 아래
                    // 조회에서 기존 계정을 그대로 사용한다.
                    if (nicknameExists(nickname)) nickname = defaultNickname();
                    else break;
                }
            }
            // A locking read sees a row committed by a concurrent first login,
            // which this transaction's earlier consistent snapshot may not include.
            var created = jdbc.query("SELECT id, nickname, email, profile_image_url, profile_completed, role FROM users WHERE google_uid = ? FOR UPDATE",
                    (rs, row) -> new LoginUser(rs.getLong("id"), rs.getString("nickname"), rs.getString("email"), rs.getString("profile_image_url"), 0, 0, !rs.getBoolean("profile_completed"), rs.getString("role")), token.getUid());
            // 자동 닉네임이 재시도 내내 겹쳐 계정을 만들지 못한 경우다. 500 대신 다시 시도하라고 알린다.
            if (created.isEmpty())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "계정을 만들지 못했습니다. 잠시 후 다시 로그인해 주세요.");
            user = created.get(0);
        }
        boolean admin = "ADMIN".equalsIgnoreCase(user.role());
        if (isConfiguredAdmin(token) && !admin) {
            jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
            user = withRole(user, "ADMIN");
        } else if (admin && adminConfigured() && !isConfiguredAdmin(token)) {
            // 설정에서 빠진 관리자는 다음 요청부터 일반 회원이 된다. 설정이 통째로 비어
            // 있으면 배포 실수일 수 있으므로 회수하지 않고 경고만 남긴다.
            jdbc.update("UPDATE users SET role = 'USER' WHERE id = ? AND role = 'ADMIN'", user.id());
            log.warn("관리자 설정에 없는 계정 {}의 관리자 권한을 회수했습니다.", user.id());
            user = withRole(user, "USER");
        } else if (admin && !adminConfigured()) {
            log.warn("관리자 설정이 비어 있어 계정 {}의 관리자 권한을 확인하지 못했습니다.", user.id());
        }
        return user;
    }

    private static LoginUser withRole(LoginUser user, String role) {
        return new LoginUser(user.id(), user.nickname(), user.email(), user.profileImageUrl(),
                user.attendanceReward(), user.attendanceStreak(), user.requiresNickname(), role);
    }

    private boolean adminConfigured() {
        return (adminGoogleUid != null && !adminGoogleUid.isBlank()) || (adminGoogleEmail != null && !adminGoogleEmail.isBlank());
    }

    private boolean isConfiguredAdmin(FirebaseToken token) {
        String uid = adminGoogleUid == null ? "" : adminGoogleUid.trim();
        String email = adminGoogleEmail == null ? "" : adminGoogleEmail.trim();
        boolean uidMatches = !uid.isBlank() && uid.equals(token.getUid());
        boolean emailMatches = token.isEmailVerified() && !email.isBlank() && email.equalsIgnoreCase(token.getEmail() == null ? "" : token.getEmail());
        return uidMatches || emailMatches;
    }

    /** 관리자가 부적절한 닉네임을 비울 때도 신규 가입과 같은 형식을 쓴다. */
    String generateNickname() { return defaultNickname(); }

    private String defaultNickname() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String nickname = String.format("슈엔단%04d", ThreadLocalRandom.current().nextInt(10_000));
            if (!nicknameExists(nickname)) return nickname;
        }
        // Keep short names when available without imposing a 10,000-account cap.
        return "슈엔단" + UUID.randomUUID().toString().replace("-", "");
    }

    private boolean nicknameExists(String nickname) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE nickname = ?", Integer.class, nickname);
        return count != null && count > 0;
    }

    LoginUser grantAttendanceReward(LoginUser user) {
        LocalDate today = LocalDate.now(clock.withZone(ATTENDANCE_ZONE));
        Integer previous = jdbc.query("SELECT streak_day FROM attendance_rewards WHERE user_id = ? AND rewarded_on = ?",
                (rs, row) -> rs.getInt(1), user.id(), today).stream().findFirst().orElse(null);
        if (previous != null) return new LoginUser(user.id(), user.nickname(), user.email(), user.profileImageUrl(), 0, previous, user.requiresNickname(), user.role());
        var last = jdbc.query("SELECT rewarded_on, streak_day FROM attendance_rewards WHERE user_id = ? ORDER BY rewarded_on DESC LIMIT 1",
                (rs, row) -> Map.of("date", rs.getDate(1).toLocalDate(), "streak", rs.getInt(2)), user.id());
        int streak = !last.isEmpty() && ((LocalDate) last.get(0).get("date")).plusDays(1).equals(today) ? (int) last.get(0).get("streak") + 1 : 1;
        long reward = Math.min(streak, 5) * ATTENDANCE_STEP;
        // Parallel first requests of the day race here. The unique key admits one
        // row, and only the request that inserted it pays the reward.
        // reward_cash stays 0: the reward goes to the personal wallet, and reward_cash means trading cash that returns leave out.
        int inserted = jdbc.update("INSERT IGNORE INTO attendance_rewards (user_id, rewarded_on, streak_day, reward_cash, wallet_cash) VALUES (?, ?, ?, 0, ?)", user.id(), today, streak, reward);
        if (inserted == 0) return new LoginUser(user.id(), user.nickname(), user.email(), user.profileImageUrl(), 0, streak, user.requiresNickname(), user.role());
        // Keyed by member and day: an admin season reset deletes the reward rows but not the wallet,
        // so a row-based key would let the same day's attendance pay twice.
        com.gamestock.backend.market.PersonalWallet.credit(jdbc, user.id(), "attendance:" + user.id() + ":" + today,
                com.gamestock.backend.market.PersonalWallet.ATTENDANCE, reward, "출석 " + streak + "일째 보상");
        return new LoginUser(user.id(), user.nickname(), user.email(), user.profileImageUrl(), reward, streak, user.requiresNickname(), user.role());
    }

    /** 로그인하지 않는 운영 봇이 접속한 날, 회원 로그인과 같은 규칙으로 출석 보상을 받는다. 오늘 받은 금액을 돌려준다. */
    public long grantAttendanceReward(long userId) {
        return grantAttendanceReward(new LoginUser(userId, null, null, null, 0, 0, false)).attendanceReward();
    }

    /** 오늘의 연속 일수와 한 달치 출석 기록. 달을 비우면 한국시간 이번 달을 돌려준다. */
    public Attendance attendance(long userId, String month) {
        LocalDate today = LocalDate.now(clock.withZone(ATTENDANCE_ZONE));
        java.time.YearMonth shown;
        try {
            shown = month == null || month.isBlank() ? java.time.YearMonth.from(today) : java.time.YearMonth.parse(month.trim());
        } catch (java.time.format.DateTimeParseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "달은 2026-10 형식으로 보내 주세요.");
        }
        var days = jdbc.query("SELECT rewarded_on, streak_day, reward_cash + wallet_cash FROM attendance_rewards WHERE user_id = ? AND rewarded_on BETWEEN ? AND ? ORDER BY rewarded_on",
                (rs, row) -> new AttendanceDay(rs.getDate(1).toLocalDate().toString(), rs.getInt(2), rs.getLong(3)),
                userId, shown.atDay(1), shown.atEndOfMonth());
        var last = jdbc.query("SELECT rewarded_on, streak_day, reward_cash + wallet_cash FROM attendance_rewards WHERE user_id = ? ORDER BY rewarded_on DESC LIMIT 1",
                (rs, row) -> new AttendanceDay(rs.getDate(1).toLocalDate().toString(), rs.getInt(2), rs.getLong(3)), userId);
        var totals = jdbc.queryForMap("SELECT COUNT(*) AS days, COALESCE(SUM(reward_cash + wallet_cash), 0) AS reward, COALESCE(MAX(streak_day), 0) AS longest FROM attendance_rewards WHERE user_id = ?", userId);
        LocalDate lastDate = last.isEmpty() ? null : LocalDate.parse(last.get(0).date());
        boolean checkedToday = today.equals(lastDate);
        // 어제까지 이어 온 연속은 오늘 출석 전까지 살아 있다.
        int streak = lastDate != null && !lastDate.plusDays(1).isBefore(today) ? last.get(0).streakDay() : 0;
        long todayReward = checkedToday ? last.get(0).reward() : 0;
        // 오늘 받았으면 내일 보상, 아직이면 오늘 받을 보상이다. 둘 다 연속을 하루 더 이었을 때의 금액.
        long nextReward = Math.min(streak + 1, 5) * ATTENDANCE_STEP;
        return new Attendance(today.toString(), checkedToday, streak, todayReward, nextReward,
                ((Number) totals.get("longest")).intValue(), ((Number) totals.get("days")).intValue(),
                ((Number) totals.get("reward")).longValue(), shown.toString(), days);
    }

    public record Attendance(String today, boolean checkedToday, int streak, long todayReward, long nextReward,
                             int longestStreak, int totalDays, long totalReward, String month, java.util.List<AttendanceDay> days) { }
    public record AttendanceDay(String date, int streakDay, long reward) { }

    public record LoginUser(long id, String nickname, String email, String profileImageUrl, long attendanceReward,
                            int attendanceStreak, boolean requiresNickname, String role) {
        public LoginUser(long id, String nickname, String email, String profileImageUrl, long attendanceReward,
                         int attendanceStreak, boolean requiresNickname) {
            this(id, nickname, email, profileImageUrl, attendanceReward, attendanceStreak, requiresNickname, "USER");
        }
    }
    public record Profile(String nickname, String email, String profileImageUrl, boolean profileCompleted,
                          boolean resetAvailable, boolean nicknameChangeAvailable, String nicknameChangeAvailableAt,
                          boolean admin, String tradeBannedUntil, String tradeBanReason) {
        public Profile(String nickname, String email, String profileImageUrl, boolean profileCompleted) {
            this(nickname, email, profileImageUrl, profileCompleted, true, true, null, false, null, null);
        }
    }
    public record ProfileUpdate(String nickname, String profileImageUrl) { }
    public record MobileCode(String code, String expiresAt) { }
    public record MobileCodeExchange(String code, String state) { }
    public record MobileToken(String customToken) { }
    private record PendingMobileCode(String customToken, Instant expiresAt) { }
}
