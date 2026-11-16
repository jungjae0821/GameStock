package com.gamestock.backend.auth;

import com.gamestock.backend.market.AdminSupport;
import com.gamestock.backend.market.OrderService;
import com.google.firebase.auth.FirebaseAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 회원 완전 삭제. 관리자가 지우는 경우와 회원 본인의 탈퇴가 같은 절차를 쓴다. 회원을 가리키는 행을 외래 키를 따라 모두 지운 뒤 users 행을 지운다.
 * 체결·정산은 상대 회원의 기록이기도 해서 상대의 거래 내역에서도 함께 사라진다.
 * 감사 기록(admin_actions, admin_audit)은 외래 키가 없어 그대로 남는다.
 *
 * 외래 키 목록은 information_schema에서 읽는다. 나중에 회원을 참조하는 표가 늘어도
 * 이 목록을 손으로 고치지 않아도 된다.
 */
@Service
public class UserDeletionService {
    private static final Logger log = LoggerFactory.getLogger(UserDeletionService.class);
    private static final int CHUNK = 500;
    private static final int MAX_DEPTH = 32;
    /** 외래 키 없이 user_id만 들고 있는 개인 데이터 표. */
    private static final List<String> LOOSE_USER_TABLES = List.of("profile_photos", "open_api_requests");

    private final JdbcTemplate jdbc;
    private final AdminService admin;
    private final AdminSupport audit;
    private final OrderService orders;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();

    public UserDeletionService(JdbcTemplate jdbc, AdminService admin, AdminSupport audit, OrderService orders,
                               PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.admin = admin;
        this.audit = audit;
        this.orders = orders;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * 1) 대상을 정지해 새 주문을 막고 2) 미체결·자동 주문을 취소한 뒤 3) 한 트랜잭션에서 지운다.
     * 매칭 엔진이 주문을 메모리에 들고 있으므로 주문은 DB에서 바로 지우지 않고 엔진을 거쳐 취소한다.
     */
    public void delete(long adminId, long userId, String confirmation) {
        AdminService.AdminUser target = admin.user(userId);
        if (adminId == userId) throw new IllegalArgumentException("자기 계정은 삭제할 수 없습니다.");
        if ("ADMIN".equalsIgnoreCase(target.role())) throw new IllegalArgumentException("관리자 계정은 삭제할 수 없습니다.");
        requireNickname(target, confirmation);
        remove(target, adminId, "USER_DELETE", "회원 완전 삭제", true);
    }

    /**
     * 회원 본인의 탈퇴. 관리자 삭제와 같은 절차로 지운 뒤 Firebase 로그인 계정도 지운다.
     * 실수로 누르지 않도록 지금 닉네임을 확인값으로 받는다.
     * @return Firebase 로그인 계정까지 지웠는지. false면 브라우저가 직접 지우도록 한다.
     */
    public boolean deleteSelf(long userId, String confirmation) {
        AdminService.AdminUser target = admin.user(userId);
        if ("ADMIN".equalsIgnoreCase(target.role()))
            throw new IllegalArgumentException("관리자 계정은 탈퇴할 수 없습니다. 다른 관리자에게 권한 해제를 요청해 주세요.");
        requireNickname(target, confirmation);
        // 탈퇴한 회원의 이메일이 감사 기록에 남지 않게 닉네임만 적는다.
        remove(target, userId, "SELF_DELETE", "회원 탈퇴", false);
        return deleteFirebaseUser(target.uid());
    }

    private static void requireNickname(AdminService.AdminUser target, String confirmation) {
        if (confirmation == null || !confirmation.trim().equals(target.nickname()))
            throw new IllegalArgumentException("확인용 닉네임이 일치하지 않습니다.");
    }

    /** DB가 이미 지워졌으므로 여기서 실패해도 탈퇴는 끝난 것으로 본다. 다시 로그인하면 빈 새 계정이 만들어질 뿐이다. */
    boolean deleteFirebaseUser(String uid) {
        if (uid == null || uid.isBlank()) return true;
        try {
            FirebaseAuth.getInstance().deleteUser(uid);
            return true;
        } catch (Exception error) {
            log.warn("Firebase 로그인 계정을 지우지 못했습니다 ({})", error.getClass().getSimpleName());
            return false;
        }
    }

    private void remove(AdminService.AdminUser target, long actorId, String action, String reason, boolean keepContact) {
        long userId = target.id();
        if (pendingSettlements(userId) > 0)
            throw new IllegalArgumentException("정산 대기 중인 체결이 있어 지금은 삭제할 수 없습니다. 정산이 끝난 뒤 다시 시도해 주세요.");

        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE users SET trade_banned_until = ?, trade_ban_reason = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofDays(1))), "계정 삭제 처리 중", userId));
        jdbc.update("UPDATE stop_orders SET status = 'CANCELLED', message = '계정 삭제로 취소했습니다.' WHERE user_id = ? AND status = 'ACTIVE'", userId);
        try {
            orders.cancelAll(userId).join();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("미체결 주문을 취소하지 못해 삭제를 멈췄습니다. 계정은 하루 이용 정지 상태로 남아 있습니다.", error);
        }

        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
            Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ? AND status = 'OPEN'", Integer.class, userId);
            if ((open != null && open > 0) || pendingSettlements(userId) > 0)
                throw new IllegalArgumentException("삭제 중에 새 체결이 생겨 멈췄습니다. 계정은 하루 이용 정지 상태로 남아 있으니 잠시 뒤 다시 시도해 주세요.");
            int removed = 0;
            for (String table : LOOSE_USER_TABLES)
                if (tableExists(table)) removed += jdbc.update("DELETE FROM `" + table + "` WHERE user_id = ?", userId);
            removed += purge("users", "id", List.of(userId), 0);
            jdbc.update("INSERT INTO admin_actions (admin_id, target_user_id, action, detail) VALUES (?, ?, ?, ?)",
                    actorId, userId, action, truncate(keepContact
                            ? target.nickname() + " · " + (target.email() == null ? "이메일 없음" : target.email())
                            : target.nickname(), 300));
            audit.audit(actorId, action, userId, reason, keepContact ? target : Map.of("nickname", target.nickname()),
                    Map.of("deleted", true, "rows", removed));
        });
    }

    private int pendingSettlements(long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM settlements WHERE status = 'PENDING' AND (buyer_id = ? OR seller_id = ?)",
                Integer.class, userId, userId);
        return count == null ? 0 : count;
    }

    private record Reference(String table, String column, String referencedColumn) { }

    private List<Reference> referencesTo(String table) {
        return jdbc.query("""
                SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
                WHERE REFERENCED_TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME = ?
                ORDER BY TABLE_NAME, COLUMN_NAME
                """, (rs, row) -> new Reference(rs.getString(1), rs.getString(2), rs.getString(3)), table);
    }

    /** table에서 column 값이 values인 행을, 그 행을 가리키는 자식 행부터 지운다. 자기 참조(답글)도 같은 방식으로 풀린다. */
    private int purge(String table, String column, List<?> values, int depth) {
        if (values.isEmpty()) return 0;
        if (depth > MAX_DEPTH) throw new IllegalStateException("참조가 너무 깊어 회원을 삭제하지 못했습니다: " + table);
        int removed = 0;
        for (Reference reference : referencesTo(table)) {
            List<?> keys = reference.referencedColumn().equals(column) ? values : select(table, reference.referencedColumn(), column, values);
            removed += purge(reference.table(), reference.column(), keys, depth + 1);
        }
        for (List<?> chunk : chunks(values))
            removed += jdbc.update("DELETE FROM `" + table + "` WHERE `" + column + "` IN (" + placeholders(chunk.size()) + ")", chunk.toArray());
        return removed;
    }

    private List<Object> select(String table, String wanted, String column, List<?> values) {
        List<Object> result = new ArrayList<>();
        for (List<?> chunk : chunks(values))
            result.addAll(jdbc.queryForList("SELECT DISTINCT `" + wanted + "` FROM `" + table + "` WHERE `" + column + "` IN (" + placeholders(chunk.size()) + ") AND `" + wanted + "` IS NOT NULL",
                    Object.class, chunk.toArray()));
        return result;
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", Integer.class, table);
        return count != null && count > 0;
    }

    private static List<? extends List<?>> chunks(List<?> values) {
        if (values.size() <= CHUNK) return Collections.singletonList(values);
        List<List<?>> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i += CHUNK) result.add(values.subList(i, Math.min(values.size(), i + CHUNK)));
        return result;
    }

    private static String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }

    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
