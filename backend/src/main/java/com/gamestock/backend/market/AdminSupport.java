package com.gamestock.backend.market;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.auth.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Root admins still come exclusively from the verified Firebase allowlist. */
@Service
public class AdminSupport {
    public static final Set<String> PERMISSIONS = Set.of("READ", "USERS", "MARKET", "CONTENT", "REWARDS", "SEASONS");
    static final String HUMAN = "u.google_uid IS NOT NULL AND u.password_hash NOT IN ('BOT','TRADER','GUEST') AND u.username <> 'demo' AND u.role <> 'ADMIN'";
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;
    public AdminSupport(JdbcTemplate db, AuthService auth, ObjectMapper json) { this.db=db;this.auth=auth;this.json=json; }

    public long require(String authorization, String permission) {
        var user=auth.requireUser(authorization);
        if ("ADMIN".equalsIgnoreCase(user.role())) return user.id();
        List<String> grants=db.queryForList("SELECT permission FROM admin_permissions WHERE user_id=?",String.class,user.id());
        if (!grants.contains(permission) && !("READ".equals(permission) && !grants.isEmpty()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"관리자 권한이 필요합니다: "+permission);
        return user.id();
    }
    public long root(String authorization) { return auth.requireAdmin(authorization).id(); }
    /**
     * 위임 관리자는 일반 회원 계정이라 지급·복구 대상에 자기 자신도 들어간다. 감사 기록만으로는
     * 막지 못하므로 자기 계정을 대상으로 하는 조치는 거절한다. 루트 관리자는 시장 밖 계정이라 대상이 될 수 없다.
     */
    public static void otherAccount(long actor, long target) {
        if (actor == target)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "자기 계정에는 이 조치를 할 수 없습니다. 다른 관리자에게 요청해 주세요.");
    }
    static String text(String value,int max,String label) {
        if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException(label+"을(를) 1~"+max+"자로 입력해 주세요.");
        return value.trim();
    }
    static String reason(String value) {return text(value,300,"조치 사유");}
    static String requestKey(String value){text(value,80,"중복 방지 키");if(!value.matches("[A-Za-z0-9][A-Za-z0-9:_-]{0,79}"))throw new IllegalArgumentException("중복 방지 키는 영문·숫자와 : _ - 만 사용할 수 있습니다.");return value;}
    static void page(int limit,long before) {if(limit<1||limit>100||before<0)throw new IllegalArgumentException("limit은 1~100, before는 0 이상이어야 합니다.");}
    static void lock(JdbcTemplate db) {db.queryForObject("SELECT id FROM market_locks WHERE id=1 FOR UPDATE",Integer.class);}
    static Map<String,Object> human(JdbcTemplate db,long user) {
        var rows=db.queryForList("SELECT u.id,u.cash,u.loan_balance,u.nickname FROM users u WHERE u.id=? AND "+HUMAN+" FOR UPDATE",user);
        if(rows.isEmpty())throw new IllegalArgumentException("일반 회원 계정을 찾을 수 없습니다.");
        return rows.get(0);
    }
    public void audit(long actor,String action,Object target,String reason,Object before,Object after) {
        db.update("INSERT INTO admin_audit(admin_id,action,target,reason,before_json,after_json) VALUES(?,?,?,?,?,?)",
            actor,action,String.valueOf(target),reason(reason),encode(before),encode(after));
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalArgumentException("변경 기록을 저장하지 못했습니다.",e);}
    }
}
