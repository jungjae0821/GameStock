package com.gamestock.backend.market;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Stock chat only. Discussion posts and replies use the upstream StockTalkService. */
@Service
public class DiscussionService {
    public record Entry(long id, String stockCode, long authorId, String nickname, Long parentId,
                        String kind, String title, String body, boolean deleted, String createdAt, String updatedAt) {}
    public record Write(String title, String body) {}
    public record Page(List<Entry> items, Long nextCursor, boolean hasMore) {}
    private final JdbcTemplate db;
    public DiscussionService(JdbcTemplate db) { this.db = db; }
    private static final String SELECT = """
        SELECT d.*,s.stock_code,u.nickname FROM stock_discussions d
        JOIN stocks s ON s.id=d.stock_id JOIN users u ON u.id=d.user_id
        """;
    private final org.springframework.jdbc.core.RowMapper<Entry> mapper = (r,n) -> new Entry(
        r.getLong("id"), r.getString("stock_code"), r.getLong("user_id"), r.getString("nickname"),
        r.getObject("parent_id", Long.class), r.getString("kind"), r.getBoolean("deleted") ? "" : r.getString("title"),
        r.getBoolean("deleted") ? "" : r.getString("body"), r.getBoolean("deleted"),
        r.getTimestamp("created_at").toInstant().toString(), r.getTimestamp("updated_at").toInstant().toString());

    long stock(String code) {
        return db.query("SELECT id FROM stocks WHERE stock_code=?", (r,n)->r.getLong(1), normalize(code))
            .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"종목이 없습니다."));
    }
    static String normalize(String code) { return code == null ? "" : code.trim().toUpperCase(Locale.ROOT); }
    public Entry get(String code, long id) {
        return db.query(SELECT+" WHERE d.id=? AND s.stock_code=? AND d.kind='CHAT'",mapper,id,normalize(code)).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"게시물이 없습니다."));
    }
    public Page list(String code, Long before, Long after, int limit) {
        if (limit<1 || limit>100 || (before!=null && after!=null) || (before!=null && before<1) || (after!=null && after<0))
            throw new IllegalArgumentException("커서와 limit(1~100)을 확인해 주세요.");
        long stock = stock(code);
        boolean ascending = after!=null;
        List<Object> args = new ArrayList<>(List.of(stock,"CHAT"));
        String filter = " AND d.parent_id IS NULL";
        if(before!=null) { filter+=" AND d.id<?"; args.add(before); }
        if(after!=null) { filter+=" AND d.id>?"; args.add(after); }
        args.add(limit+1);
        List<Entry> found=db.query(SELECT+" WHERE d.stock_id=? AND d.kind=?"+filter+
            " ORDER BY d.id "+(ascending?"ASC":"DESC")+" LIMIT ?",mapper,args.toArray());
        boolean more=found.size()>limit;
        List<Entry> items=List.copyOf(found.subList(0,Math.min(limit,found.size())));
        return new Page(items,items.isEmpty()?null:items.get(items.size()-1).id(),more);
    }
    @Transactional
    public Entry create(long user, String code, Write write) {
        String body=checked(write==null?null:write.body(),1000,"내용");
        long stock=stock(code);
        reserve(user,"CHAT",30);
        db.update("INSERT INTO stock_discussions(stock_id,user_id,kind,body) VALUES(?,?,?,?)",stock,user,"CHAT",body);
        return get(code,db.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
    }
    @Transactional
    public void delete(long user, boolean admin, String code, long id) {
        Entry entry=get(code,id);
        if(entry.authorId()!=user && !admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"삭제 권한이 없습니다.");
        db.update("UPDATE stock_discussions SET deleted=TRUE,title='',body='',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);
    }
    static String checked(String text,int max,String label) {
        if(text==null || text.isBlank() || text.length()>max) throw new IllegalArgumentException(label+"은 1~"+max+"자입니다.");
        return text.trim();
    }
    void reserve(long user,String channel,int max) {
        long minute=System.currentTimeMillis()/60000;
        db.update("""
            INSERT INTO research_write_limits(user_id,channel,window_no,actions) VALUES(?,?,?,1)
            ON DUPLICATE KEY UPDATE actions=IF(window_no=VALUES(window_no),actions+1,1),window_no=VALUES(window_no)
            """,user,channel,minute);
        Integer count=db.queryForObject("SELECT actions FROM research_write_limits WHERE user_id=? AND channel=?",Integer.class,user,channel);
        if(count!=null && count>max) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"잠시 후 다시 작성해 주세요.");
    }
}
