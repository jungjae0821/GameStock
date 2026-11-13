package com.gamestock.backend.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 관리자 화면의 계정 조회, 거래 제한, 닉네임 강제 변경을 담당한다. */
@Service
public class AdminService {
    static final int MAX_BAN_DAYS = 365;
    private static final int PAGE_SIZE = 50;
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final Clock clock;
    private com.gamestock.backend.market.AdminSupport audit;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void configureAudit(com.gamestock.backend.market.AdminSupport audit){this.audit=audit;}

    @org.springframework.beans.factory.annotation.Autowired
    public AdminService(JdbcTemplate jdbc, AuthService auth) { this(jdbc, auth, Clock.systemUTC()); }

    AdminService(JdbcTemplate jdbc, AuthService auth, Clock clock) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.clock = clock;
    }

    /** 이메일·닉네임·UID 일부로 검색한다. 검색어가 없으면 최근 가입 순으로 보여 준다. */
    public List<AdminUser> users(String query) {
        String q = query == null ? "" : query.trim();
        String select = "SELECT id, google_uid, email, nickname, role, persona, cash, created_at, nickname_changed_at, trade_banned_until, trade_ban_reason FROM users";
        if (q.isEmpty())
            return jdbc.query(select + " WHERE google_uid IS NOT NULL ORDER BY id DESC LIMIT " + PAGE_SIZE, this::mapUser);
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query(select + " WHERE google_uid IS NOT NULL AND (email LIKE ? OR nickname LIKE ? OR google_uid LIKE ? OR CAST(id AS CHAR) = ?) ORDER BY id DESC LIMIT " + PAGE_SIZE,
                this::mapUser, like, like, like, q);
    }

    @Transactional
    public AdminUser banTrading(long adminId, long userId, TradeBan request) {
        int days = request == null || request.days() == null ? 0 : request.days();
        if (days < 1 || days > MAX_BAN_DAYS)
            throw new IllegalArgumentException("제한 기간은 1~" + MAX_BAN_DAYS + "일로 입력해 주세요.");
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() > 200) throw new IllegalArgumentException("사유는 200자 이내로 입력해 주세요.");
        AdminUser target = lockUser(userId);
        if ("ADMIN".equalsIgnoreCase(target.role()))
            throw new IllegalArgumentException("관리자 계정은 거래를 제한할 수 없습니다.");
        Instant until = clock.instant().plus(Duration.ofDays(days));
        jdbc.update("UPDATE users SET trade_banned_until = ?, trade_ban_reason = ? WHERE id = ?",
                Timestamp.from(until), reason.isEmpty() ? null : reason, userId);
        log(adminId, userId, "TRADE_BAN", days + "일" + (reason.isEmpty() ? "" : " · " + reason));
        AdminUser after=lockUser(userId);
        if(audit!=null)audit.audit(adminId,"TRADE_BAN",userId,reason.isEmpty()?"관리자 이용 제한":reason,target,after);
        return after;
    }

    @Transactional
    public AdminUser liftTradeBan(long adminId, long userId) {
        AdminUser before=lockUser(userId);
        jdbc.update("UPDATE users SET trade_banned_until = NULL, trade_ban_reason = NULL WHERE id = ?", userId);
        log(adminId, userId, "TRADE_BAN_LIFT", "");
        AdminUser after=lockUser(userId);
        if(audit!=null)audit.audit(adminId,"TRADE_BAN_LIFT",userId,"관리자 이용 제한 해제",before,after);
        return after;
    }

    /**
     * 3일 변경 제한을 건너뛰고 닉네임을 바꾼다. 빈 값이면 신규 가입과 같은 자동
     * 닉네임을 붙인다. 변경 시각을 지워 사용자가 곧바로 적절한 닉네임을 고를 수 있다.
     */
    @Transactional
    public AdminUser forceNickname(long adminId, long userId, NicknameChange request) {
        String nickname = request == null || request.nickname() == null ? "" : request.nickname().trim();
        if (nickname.isEmpty()) nickname = auth.generateNickname();
        if (nickname.length() < AuthService.NICKNAME_MIN_LENGTH || nickname.length() > AuthService.NICKNAME_MAX_LENGTH)
            throw new IllegalArgumentException("닉네임은 2~10자로 입력해 주세요.");
        AdminUser target = lockUser(userId);
        if (nickname.equals(target.nickname())) return target;
        try {
            jdbc.update("UPDATE users SET nickname = ?, nickname_changed_at = NULL WHERE id = ?", nickname, userId);
        } catch (DuplicateKeyException error) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다.");
        }
        log(adminId, userId, "NICKNAME_FORCE", target.nickname() + " → " + nickname);
        AdminUser after=lockUser(userId);
        if(audit!=null)audit.audit(adminId,"NICKNAME_FORCE",userId,"닉네임 강제 변경",target,after);
        return after;
    }

    public AdminUser user(long userId) {
        var rows = jdbc.query("SELECT id, google_uid, email, nickname, role, persona, cash, created_at, nickname_changed_at, trade_banned_until, trade_ban_reason FROM users WHERE id = ?",
                this::mapUser, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "계정을 찾을 수 없습니다.");
        return rows.get(0);
    }

    private AdminUser lockUser(long userId) {
        var rows = jdbc.query("SELECT id, google_uid, email, nickname, role, persona, cash, created_at, nickname_changed_at, trade_banned_until, trade_ban_reason FROM users WHERE id = ? FOR UPDATE",
                this::mapUser, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "계정을 찾을 수 없습니다.");
        return rows.get(0);
    }

    private void log(long adminId, long userId, String action, String detail) {
        jdbc.update("INSERT INTO admin_actions (admin_id, target_user_id, action, detail) VALUES (?, ?, ?, ?)",
                adminId, userId, action, detail);
    }

    private AdminUser mapUser(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp until = rs.getTimestamp("trade_banned_until");
        boolean banned = until != null && clock.instant().isBefore(until.toInstant());
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp changed = rs.getTimestamp("nickname_changed_at");
        return new AdminUser(rs.getLong("id"), rs.getString("google_uid"), rs.getString("email"), rs.getString("nickname"),
                rs.getString("role"), rs.getLong("cash"),
                created == null ? null : created.toInstant().toString(),
                changed == null ? null : changed.toInstant().toString(),
                banned ? until.toInstant().toString() : null,
                banned ? rs.getString("trade_ban_reason") : null,
                rs.getString("persona"));
    }

    /** {@code persona}는 운영 봇의 성격(LONG·SCALP·NEWBIE)이고 일반 회원은 null이다. */
    public record AdminUser(long id, String uid, String email, String nickname, String role, long cash,
                            String createdAt, String nicknameChangedAt, String tradeBannedUntil, String tradeBanReason,
                            String persona) { }
    public record TradeBan(Integer days, String reason) { }
    public record NicknameChange(String nickname) { }
}
