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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AuthService {
    private static final long MOBILE_CODE_TTL_SECONDS = 120;
    private final JdbcTemplate jdbc;
    private final Map<String, PendingMobileCode> mobileCodes = new ConcurrentHashMap<>();
    /**
     * The administrator is still authenticated by Firebase. These optional
     * allowlists only decide which verified Google identity receives ADMIN on
     * first login; they never create an unauthenticated bypass.
     */
    @Value("${gamestock.auth.admin-google-uid:}")
    private String adminGoogleUid;
    @Value("${gamestock.auth.admin-google-email:}")
    private String adminGoogleEmail;

    public AuthService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public LoginUser requireUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        try {
            FirebaseToken token = FirebaseAuth.getInstance().verifyIdToken(authorization.substring(7));
            return findOrCreate(token);
        } catch (ResponseStatusException error) { throw error; }
        catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 로그인입니다.");
        }
    }

    public LoginUser login(String authorization) { return requireUser(authorization); }

    public MobileCode issueMobileCode(String authorization) {
        FirebaseToken token = verifyToken(authorization);
        try {
            String customToken = FirebaseAuth.getInstance().createCustomToken(token.getUid());
            String code = UUID.randomUUID().toString().replace("-", "");
            Instant expiresAt = Instant.now().plusSeconds(MOBILE_CODE_TTL_SECONDS);
            mobileCodes.put(code, new PendingMobileCode(customToken, expiresAt));
            mobileCodes.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
            return new MobileCode(code, expiresAt.toString());
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "모바일 로그인 코드를 만들지 못했습니다.");
        }
    }

    public MobileToken exchangeMobileCode(MobileCodeExchange request) {
        if (request == null || request.code() == null || request.code().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "로그인 인증 코드가 필요합니다.");
        PendingMobileCode pending = mobileCodes.remove(request.code().trim());
        if (pending == null || pending.expiresAt().isBefore(Instant.now()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "만료되었거나 이미 사용한 로그인 코드입니다.");
        return new MobileToken(pending.customToken());
    }

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
            return FirebaseAuth.getInstance().verifyIdToken(authorization.substring(7));
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 로그인입니다.");
        }
    }

    public Profile profile(long userId) {
        return jdbc.queryForObject("SELECT nickname, email, profile_image_url, profile_completed, reset_used_at FROM users WHERE id = ?",
                (rs, row) -> new Profile(rs.getString("nickname"), rs.getString("email"),
                        rs.getString("profile_image_url"), rs.getBoolean("profile_completed"), rs.getTimestamp("reset_used_at") == null), userId);
    }

    @Transactional
    public Profile updateProfile(long userId, ProfileUpdate update) {
        String nickname = update.nickname() == null ? "" : update.nickname().trim();
        if (nickname.length() < 2 || nickname.length() > 50)
            throw new IllegalArgumentException("닉네임은 2~50자로 입력해 주세요.");
        String image = update.profileImageUrl() == null ? "" : update.profileImageUrl().trim();
        if (image.length() > 500) throw new IllegalArgumentException("프로필 이미지 주소가 너무 깁니다.");
        try {
            jdbc.update("UPDATE users SET nickname = ?, profile_image_url = ?, profile_completed = TRUE WHERE id = ?",
                    nickname, image, userId);
        } catch (DuplicateKeyException error) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다.");
        }
        return profile(userId);
    }

    private LoginUser findOrCreate(FirebaseToken token) {
        var existing = jdbc.query("SELECT id, nickname, email, profile_image_url, profile_completed, role FROM users WHERE google_uid = ?",
                (rs, row) -> new LoginUser(rs.getLong("id"), rs.getString("nickname"), rs.getString("email"), rs.getString("profile_image_url"), 0, 0, !rs.getBoolean("profile_completed"), rs.getString("role")), token.getUid());
        LoginUser user;
        if (!existing.isEmpty()) user = existing.get(0);
        else {
            String email = token.getEmail() == null ? "" : token.getEmail();
            String picture = token.getPicture() == null ? "" : token.getPicture();
            String username = "google_" + token.getUid();
            String nickname = defaultNickname();
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    // 신규 계정은 자동 닉네임으로 바로 생성한다. 사용자는 이후
                    // 마이페이지에서 원하는 닉네임으로 자유롭게 변경할 수 있다.
                    jdbc.update("INSERT INTO users (username, password_hash, nickname, google_uid, email, profile_image_url, profile_completed, cash) VALUES (?, 'GOOGLE', ?, ?, ?, ?, TRUE, 1000000)",
                            username, nickname, token.getUid(), email, picture);
                    break;
                } catch (DuplicateKeyException error) {
                    // 닉네임 4자리 숫자가 동시에 겹친 경우에만 새 값을 뽑아
                    // 재시도한다. 같은 Google UID가 먼저 생성된 경우는 아래
                    // 조회에서 기존 계정을 그대로 사용한다.
                    if (nicknameExists(nickname)) nickname = defaultNickname();
                    else break;
                }
            }
            user = jdbc.queryForObject("SELECT id, nickname, email, profile_image_url, profile_completed, role FROM users WHERE google_uid = ?",
                    (rs, row) -> new LoginUser(rs.getLong("id"), rs.getString("nickname"), rs.getString("email"), rs.getString("profile_image_url"), 0, 0, !rs.getBoolean("profile_completed"), rs.getString("role")), token.getUid());
        }
        if (isConfiguredAdmin(token) && !"ADMIN".equalsIgnoreCase(user.role())) {
            jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
            user = new LoginUser(user.id(), user.nickname(), user.email(), user.profileImageUrl(),
                    user.attendanceReward(), user.attendanceStreak(), user.requiresNickname(), "ADMIN");
        }
        return grantAttendanceReward(user);
    }

    private boolean isConfiguredAdmin(FirebaseToken token) {
        String uid = adminGoogleUid == null ? "" : adminGoogleUid.trim();
        String email = adminGoogleEmail == null ? "" : adminGoogleEmail.trim();
        boolean uidMatches = !uid.isBlank() && uid.equals(token.getUid());
        boolean emailMatches = !email.isBlank() && email.equalsIgnoreCase(token.getEmail() == null ? "" : token.getEmail());
        return uidMatches || emailMatches;
    }

    private String defaultNickname() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String nickname = String.format("슈엔단%04d", ThreadLocalRandom.current().nextInt(10_000));
            if (!nicknameExists(nickname)) return nickname;
        }
        // 4자리 조합이 모두 사용되는 상황은 사실상 불가능하지만, DB의
        // UNIQUE 제약과 함께 동작하도록 마지막 값도 같은 형식을 유지한다.
        return String.format("슈엔단%04d", ThreadLocalRandom.current().nextInt(10_000));
    }

    private boolean nicknameExists(String nickname) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE nickname = ?", Integer.class, nickname);
        return count != null && count > 0;
    }

    private LoginUser grantAttendanceReward(LoginUser user) {
        LocalDate today = LocalDate.now();
        Integer previous = jdbc.query("SELECT streak_day FROM attendance_rewards WHERE user_id = ? AND rewarded_on = ?",
                (rs, row) -> rs.getInt(1), user.id(), today).stream().findFirst().orElse(null);
        if (previous != null) return new LoginUser(user.id(), user.nickname(), user.email(), user.profileImageUrl(), 0, previous, user.requiresNickname(), user.role());
        var last = jdbc.query("SELECT rewarded_on, streak_day FROM attendance_rewards WHERE user_id = ? ORDER BY rewarded_on DESC LIMIT 1",
                (rs, row) -> Map.of("date", rs.getDate(1).toLocalDate(), "streak", rs.getInt(2)), user.id());
        int streak = !last.isEmpty() && ((LocalDate) last.get(0).get("date")).plusDays(1).equals(today) ? (int) last.get(0).get("streak") + 1 : 1;
        long reward = Math.min(streak, 5) * 100_000L;
        jdbc.update("INSERT INTO attendance_rewards (user_id, rewarded_on, streak_day, reward_cash) VALUES (?, ?, ?, ?)", user.id(), today, streak, reward);
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reward, user.id());
        return new LoginUser(user.id(), user.nickname(), user.email(), user.profileImageUrl(), reward, streak, user.requiresNickname(), user.role());
    }

    public record LoginUser(long id, String nickname, String email, String profileImageUrl, long attendanceReward,
                            int attendanceStreak, boolean requiresNickname, String role) {
        public LoginUser(long id, String nickname, String email, String profileImageUrl, long attendanceReward,
                         int attendanceStreak, boolean requiresNickname) {
            this(id, nickname, email, profileImageUrl, attendanceReward, attendanceStreak, requiresNickname, "USER");
        }
    }
    public record Profile(String nickname, String email, String profileImageUrl, boolean profileCompleted,
                          boolean resetAvailable) {
        public Profile(String nickname, String email, String profileImageUrl, boolean profileCompleted) {
            this(nickname, email, profileImageUrl, profileCompleted, true);
        }
    }
    public record ProfileUpdate(String nickname, String profileImageUrl) { }
    public record MobileCode(String code, String expiresAt) { }
    public record MobileCodeExchange(String code, String state) { }
    public record MobileToken(String customToken) { }
    private record PendingMobileCode(String customToken, Instant expiresAt) { }
}
