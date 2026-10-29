package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class QuantProjectService {
    public record Source(String language,String source,JsonNode blocks) {}
    public record Save(String name,String stockCode,String range,Source code,int maxOrders,long maxNotional,Integer revision) {}
    public record Project(long id,long userId,String name,String stockCode,String range,String language,String source,
                          QuantCompiler.Program program,int revision,String status,int maxOrders,long maxNotional,
                          int ordersUsed,long notionalUsed,String lastBar,String updatedAt) {}
    public record Run(long id,int revision,String barTime,String side,int quantity,long limitPrice,String status,
                      String message,String createdAt,Long orderId) {}
    public record BacktestRequest(String stockCode,String range,Source code,long initialCash,int feeBps,int slippageBps) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final DiscussionService discussions;
    private final ResearchChartService charts;
    public QuantProjectService(JdbcTemplate db,ObjectMapper json,DiscussionService discussions,ResearchChartService charts) {
        this.db=db;this.json=json;this.discussions=discussions;this.charts=charts;
    }
    public QuantCompiler.Compilation compile(Source source) {
        if(source==null)throw new IllegalArgumentException("code가 필요합니다.");
        return QuantCompiler.compile(source.language(),source.source(),source.blocks());
    }
    public QuantBacktest.Result backtest(BacktestRequest request) {
        if(request==null)throw new IllegalArgumentException("백테스트 설정이 필요합니다.");
        var compiled=compile(request.code());if(!compiled.valid())throw new IllegalArgumentException(compiled.diagnostics().get(0).message());
        return QuantBacktest.run(compiled.program(),charts.candles(request.stockCode(),request.range(),true),
            request.initialCash(),request.feeBps(),request.slippageBps());
    }
    private final org.springframework.jdbc.core.RowMapper<Project> mapper=(r,n)->new Project(r.getLong("id"),r.getLong("user_id"),
        r.getString("name"),r.getString("stock_code"),r.getString("chart_range"),r.getString("language"),r.getString("source"),
        read(r.getString("program")),r.getInt("revision"),r.getString("status"),r.getInt("max_orders"),r.getLong("max_notional"),
        r.getInt("orders_used"),r.getLong("notional_used"),r.getString("last_bar"),r.getTimestamp("updated_at").toInstant().toString());
    QuantCompiler.Program read(String program) {
        try{return json.readValue(program,QuantCompiler.Program.class);}catch(Exception e){throw new IllegalStateException("저장된 전략을 읽지 못했습니다.",e);}
    }
    String write(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("전략을 직렬화하지 못했습니다.",e);}
    }
    public List<Project> list(long user) {return db.query("SELECT * FROM quant_projects WHERE user_id=? ORDER BY id DESC LIMIT 20",mapper,user);}
    public Project get(long user,long id) {
        return db.query("SELECT * FROM quant_projects WHERE id=? AND user_id=?",mapper,id,user).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"전략이 없습니다."));
    }
    @Transactional
    public Project save(long user,Long id,Save request) {
        if(request==null)throw new IllegalArgumentException("전략 설정이 필요합니다.");
        String name=DiscussionService.checked(request.name(),80,"이름");
        ResearchChartService.interval(request.range());discussions.stock(request.stockCode());
        if(request.maxOrders()<1 || request.maxOrders()>100 || request.maxNotional()<1 || request.maxNotional()>1_000_000_000_000L)
            throw new IllegalArgumentException("maxOrders는 1~100, maxNotional은 1~10^12입니다.");
        var compiled=compile(request.code());
        if(!compiled.valid())throw new IllegalArgumentException(compiled.diagnostics().get(0).message());
        discussions.reserve(user,"QUANT",20); // shared row serializes per-user quota checks
        String source="BLOCKS".equals(request.code().language())?write(request.code().blocks()):request.code().source();
        if(id==null) {
            if(db.queryForObject("SELECT COUNT(*) FROM quant_projects WHERE user_id=?",Integer.class,user)>=20)
                throw new IllegalArgumentException("전략은 최대 20개입니다.");
            db.update("""
                INSERT INTO quant_projects(user_id,name,stock_code,chart_range,language,source,program,max_orders,max_notional)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,user,name,DiscussionService.normalize(request.stockCode()),request.range(),request.code().language(),source,
                write(compiled.program()),request.maxOrders(),request.maxNotional());
            id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        } else {
            get(user,id);
            int updated=db.update("""
                UPDATE quant_projects SET name=?,stock_code=?,chart_range=?,language=?,source=?,program=?,
                  max_orders=?,max_notional=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(3)
                WHERE id=? AND user_id=? AND revision=? AND status='PAUSED'
                """,name,DiscussionService.normalize(request.stockCode()),request.range(),request.code().language(),source,
                write(compiled.program()),request.maxOrders(),request.maxNotional(),id,user,request.revision());
            if(updated==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"중지 상태와 최신 revision을 확인해 주세요.");
        }
        return get(user,id);
    }
    @Transactional
    public Project status(long user,long id,boolean active,int revision) {
        Project project=get(user,id);
        if(!active) {
            if(db.update("""
                UPDATE quant_projects SET status=IF(status IN ('RUNNING','PAUSING'),'PAUSING','PAUSED'),updated_at=CURRENT_TIMESTAMP(3)
                WHERE id=? AND user_id=? AND revision=?
                """,id,user,revision)==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"revision 충돌입니다.");
            return get(user,id);
        }
        if(active) {
            if(project.program().rules().isEmpty())throw new IllegalArgumentException("매매 규칙이 없는 지표 전략입니다.");
            if(!Boolean.TRUE.equals(db.queryForObject("""
                SELECT password_hash NOT IN ('BOT','TRADER') AND COALESCE(role,'USER')<>'ADMIN'
                  AND (trade_banned_until IS NULL OR trade_banned_until<=CURRENT_TIMESTAMP(3)) FROM users WHERE id=?
                """,Boolean.class,user)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"자동매매를 실행할 수 없는 계정입니다.");
        }
        int changed=db.update("""
            UPDATE quant_projects SET status=?,updated_at=CURRENT_TIMESTAMP(3)
            WHERE id=? AND user_id=? AND revision=? AND status IN ('PAUSED','ACTIVE')
              AND (?=FALSE OR (orders_used<max_orders AND notional_used<max_notional))
            """,active?"ACTIVE":"PAUSED",id,user,revision,active);
        if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"실행 중이거나 한도 소진 또는 revision 충돌입니다. 실행 완료 후 다시 시도해 주세요.");
        return get(user,id);
    }
    @Transactional
    public void delete(long user,long id) {
        Project project=get(user,id);
        db.queryForObject("SELECT id FROM quant_projects WHERE id=? FOR UPDATE",Long.class,project.id());
        if(!get(user,id).status().equals("PAUSED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"전략을 먼저 중지해 주세요.");
        db.update("DELETE FROM quant_runs WHERE project_id=?",id);db.update("DELETE FROM quant_projects WHERE id=? AND user_id=?",id,user);
    }
    public List<Run> runs(long user,long id,Long before,int limit) {
        get(user,id);if(limit<1 || limit>100 || (before!=null && before<1))throw new IllegalArgumentException("조회 범위를 확인해 주세요.");
        return db.query("SELECT * FROM quant_runs WHERE project_id=? AND id<? ORDER BY id DESC LIMIT ?",
            (r,n)->new Run(r.getLong("id"),r.getInt("revision"),r.getString("bar_time"),r.getString("side"),r.getInt("quantity"),
                r.getLong("limit_price"),r.getString("status"),r.getString("message"),r.getTimestamp("created_at").toInstant().toString(),r.getObject("order_id",Long.class)),
            id,before==null?Long.MAX_VALUE:before,limit);
    }
}
