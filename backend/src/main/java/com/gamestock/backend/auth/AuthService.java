package com.gamestock.backend.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
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

    private LoginUser findOrCreate(FirebaseToken token) {
        var existing = jdbc.query("SELECT id, nickname, email, profile_image_url FROM users WHERE google_uid = ?",
                (rs, row) -> new LoginUser(rs.getLong("id"), rs.getString("nickname"), rs.getString("email"), rs.getString("profile_image_url"), 0, 0), token.getUid());
        LoginUser user;
        if (!existing.isEmpty()) user = existing.get(0);
        else {
            String email = token.getEmail() == null ? "" : token.getEmail();
            String name = token.getName() == null || token.getName().isBlank() ? "GameStock 사용자" : token.getName();
            String picture = token.getPicture() == null ? "" : token.getPicture();
            try {
                jdbc.update("INSERT INTO users (username, password_hash, nickname, google_uid, email, profile_image_url, cash) VALUES (?, 'GOOGLE', ?, ?, ?, ?, 1000000)",
                        "google_" + token.getUid(), uniqueNickname(name), token.getUid(), email, picture);
            } catch (DuplicateKeyException ignored) { }
            user = jdbc.queryForObject("SELECT id, nickname, email, profile_image_url FROM users WHERE google_uid = ?",
                    (rs, row) -> new LoginUser(rs.getLong("id"), rs.getString("nickname"), rs.getString("email"), rs.getString("profile_image_url"), 0, 0), token.getUid());
        }
        return grantAttendanceReward(user);
    }

    private String uniqueNickname(String base) {
        String trimmed = base.length() > 42 ? base.substring(0, 42) : base;
        return trimmed + "_" + Long.toString(System.nanoTime(), 36).substring(5);
    }

    private LoginUser grantAttendanceReward(LoginUser user) {
        LocalDate today = LocalDate.now();
        Integer previous = jdbc.query("SELECT streak_day FROM attendance_rewards WHERE user_id = ? AND rewarded_on = ?",
                (rs, row) -> rs.getInt(1), user.id(), today).stream().findFirst().orElse(null);
        if (previous != null) return new LoginUser(user.id(), user.nickname(), user.email(), user.profileImageUrl(), 0, previous);
        var last = jdbc.query("SELECT rewarded_on, streak_day FROM attendance_rewards WHERE user_id = ? ORDER BY rewarded_on DESC LIMIT 1",
                (rs, row) -> Map.of("date", rs.getDate(1).toLocalDate(), "streak", rs.getInt(2)), user.id());
        int streak = !last.isEmpty() && ((LocalDate) last.get(0).get("date")).plusDays(1).equals(today) ? (int) last.get(0).get("streak") + 1 : 1;
        long reward = Math.min(streak, 5) * 100_000L;
        jdbc.update("INSERT INTO attendance_rewards (user_id, rewarded_on, streak_day, reward_cash) VALUES (?, ?, ?, ?)", user.id(), today, streak, reward);
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reward, user.id());
        return new LoginUser(user.id(), user.nickname(), user.email(), user.profileImageUrl(), reward, streak);
    }

    public record LoginUser(long id, String nickname, String email, String profileImageUrl, long attendanceReward, int attendanceStreak) { }
}
