package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.gamestock.backend.market.AdminSupport.*;

@Service
public class AdminContentService {
    private final JdbcTemplate db;
    private final AdminSupport support;
    public AdminContentService(JdbcTemplate db,AdminSupport support){this.db=db;this.support=support;}
    public record Content(String kind,String title,String body,String stockCode,boolean published,Instant startsAt,Instant endsAt,String reason,
                          String imageUrl,String actionLabel,String actionUrl,String dismissLabel) {
        public Content(String kind,String title,String body,String stockCode,boolean published,Instant startsAt,Instant endsAt,String reason){
            this(kind,title,body,stockCode,published,startsAt,endsAt,reason,null,null,null,null);
        }
    }
    public record Mission(boolean enabled,long rewardCash,int minimumWatchlist,String reason) { }
    public record Report(long targetUserId,String detail) { }
    public record Resolution(String status,String resolution,String reason) { }

    public List<Map<String,Object>> content(boolean onlyPublic,String kind,long before,int limit){
        page(limit,before);if(kind!=null&&!Set.of("NOTICE","NEWS","EVENT").contains(kind))throw new IllegalArgumentException("콘텐츠 종류를 확인해 주세요.");
        return db.queryForList("SELECT c.*,s.stock_code FROM admin_content c LEFT JOIN stocks s ON s.id=c.stock_id WHERE (?=FALSE OR (c.published=TRUE AND (c.starts_at IS NULL OR c.starts_at<=CURRENT_TIMESTAMP) AND (c.ends_at IS NULL OR c.ends_at>CURRENT_TIMESTAMP))) AND (? IS NULL OR c.kind=?) AND (?=0 OR c.id<?) ORDER BY c.id DESC LIMIT ?",onlyPublic,kind,kind,before,before,limit);
    }
    @Transactional
    public Map<String,Object> save(long actor,Long id,Content request){
        reason(request.reason());if(request.kind()==null||!Set.of("NOTICE","NEWS","EVENT").contains(request.kind()))throw new IllegalArgumentException("종류는 NOTICE, NEWS, EVENT입니다.");
        String title=text(request.title(),150,"제목"),body=text(request.body(),20000,"본문");
        if(request.startsAt()!=null&&request.endsAt()!=null&&!request.endsAt().isAfter(request.startsAt()))throw new IllegalArgumentException("종료 시각은 시작 시각 이후여야 합니다.");
        String image=link(request.imageUrl(),"그림 주소"),actionLabel=optional(request.actionLabel(),40,"이동 버튼 문구"),actionUrl=link(request.actionUrl(),"이동 주소"),dismissLabel=optional(request.dismissLabel(),40,"닫기 버튼 문구");
        if((actionLabel==null)!=(actionUrl==null))throw new IllegalArgumentException("이동 버튼은 문구와 주소를 함께 적어 주세요.");
        lock(db);Long stock=null;
        if(request.stockCode()!=null&&!request.stockCode().isBlank()){
            var ids=db.queryForList("SELECT id FROM stocks WHERE stock_code=?",Long.class,request.stockCode());if(ids.isEmpty())throw new IllegalArgumentException("종목을 찾을 수 없습니다.");stock=ids.get(0);
        }
        Object before=Map.of();
        if(id==null){
            db.update("INSERT INTO admin_content(kind,title,body,stock_id,published,starts_at,ends_at,image_url,action_label,action_url,dismiss_label) VALUES(?,?,?,?,?,?,?,?,?,?,?)",request.kind(),title,body,stock,request.published(),timestamp(request.startsAt()),timestamp(request.endsAt()),image,actionLabel,actionUrl,dismissLabel);
            id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        }else{
            before=existing(id);
            db.update("UPDATE admin_content SET kind=?,title=?,body=?,stock_id=?,published=?,starts_at=?,ends_at=?,image_url=?,action_label=?,action_url=?,dismiss_label=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",request.kind(),title,body,stock,request.published(),timestamp(request.startsAt()),timestamp(request.endsAt()),image,actionLabel,actionUrl,dismissLabel,id);
        }
        var after=existing(id);support.audit(actor,"CONTENT_SAVE",id,request.reason(),before,after);return after;
    }
    @Transactional
    public void delete(long actor,long id,String why){reason(why);lock(db);var before=existing(id);db.update("DELETE FROM admin_content WHERE id=?",id);support.audit(actor,"CONTENT_DELETE",id,why,before,Map.of());}
    private Map<String,Object> existing(long id){var rows=db.queryForList("SELECT * FROM admin_content WHERE id=?",id);if(rows.isEmpty())throw new IllegalArgumentException("콘텐츠를 찾을 수 없습니다.");return rows.get(0);}

    public List<Map<String,Object>> missions(){
        Map<String,Map<String,Object>> configured=new HashMap<>();for(var row:db.queryForList("SELECT * FROM admin_mission_config"))configured.put((String)row.get("mission_id"),row);
        return MarketService.MISSION_IDS.stream().map(id->configured.getOrDefault(id,Map.of("mission_id",id,"enabled",true,"reward_cash",10000,"minimum_watchlist","watch".equals(id)?1:0))).toList();
    }
    @Transactional
    public void mission(long actor,String id,Mission request){
        reason(request.reason());if(!MarketService.MISSION_IDS.contains(id))throw new IllegalArgumentException("기존 미션 ID만 설정할 수 있습니다.");
        if(request.rewardCash()<0||request.rewardCash()>1_000_000||request.minimumWatchlist()<0||request.minimumWatchlist()>100)throw new IllegalArgumentException("보상은 0~100만원, 관심종목 조건은 0~100개입니다.");
        lock(db);var before=db.queryForList("SELECT * FROM admin_mission_config WHERE mission_id=?",id);
        db.update("INSERT INTO admin_mission_config(mission_id,enabled,reward_cash,minimum_watchlist) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),reward_cash=VALUES(reward_cash),minimum_watchlist=VALUES(minimum_watchlist)",id,request.enabled(),request.rewardCash(),request.minimumWatchlist());
        support.audit(actor,"MISSION_CONFIG",id,request.reason(),before,request);
    }
    @Transactional
    public Map<String,Object> report(long reporter,Report request){
        String detail=text(request.detail(),1000,"신고 내용");if(reporter==request.targetUserId())throw new IllegalArgumentException("자신을 신고할 수 없습니다.");
        // Serialize per reporter without blocking the matching gate.
        db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,reporter);
        if(db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND google_uid IS NOT NULL",Long.class,request.targetUserId())==0)throw new IllegalArgumentException("신고 대상을 찾을 수 없습니다.");
        if(db.queryForObject("SELECT COUNT(*) FROM user_reports WHERE reporter_id=? AND created_at>=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY)",Long.class,reporter)>=10)throw new IllegalArgumentException("신고는 하루 최대 10건입니다.");
        if(db.queryForObject("SELECT COUNT(*) FROM user_reports WHERE reporter_id=? AND target_user_id=? AND status='OPEN'",Long.class,reporter,request.targetUserId())>0)throw new IllegalArgumentException("이미 검토 중인 신고가 있습니다.");
        db.update("INSERT INTO user_reports(reporter_id,target_user_id,detail) VALUES(?,?,?)",reporter,request.targetUserId(),detail);
        long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);return Map.of("id",id,"status","OPEN");
    }
    public List<Map<String,Object>> reports(String status,long before,int limit){
        page(limit,before);if(status!=null&&!Set.of("OPEN","RESOLVED","DISMISSED").contains(status))throw new IllegalArgumentException("신고 상태를 확인해 주세요.");
        return db.queryForList("SELECT * FROM user_reports WHERE (? IS NULL OR status=?) AND (?=0 OR id<?) ORDER BY id DESC LIMIT ?",status,status,before,before,limit);
    }
    @Transactional
    public void resolve(long actor,long id,Resolution request){
        reason(request.reason());text(request.resolution(),1000,"처리 결과");if(request.status()==null||!Set.of("RESOLVED","DISMISSED").contains(request.status()))throw new IllegalArgumentException("처리 상태는 RESOLVED 또는 DISMISSED입니다.");
        lock(db);var before=db.queryForList("SELECT * FROM user_reports WHERE id=? AND status='OPEN' FOR UPDATE",id);if(before.isEmpty())throw new IllegalArgumentException("처리할 열린 신고가 없습니다.");
        db.update("UPDATE user_reports SET status=?,resolution=?,resolved_by=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",request.status(),request.resolution(),actor,id);
        support.audit(actor,"REPORT_RESOLVE",id,request.reason(),before,request);
    }
    @Transactional
    public void deleteComment(long actor,long id,String why){
        reason(why);lock(db);var before=db.queryForList("SELECT * FROM stock_comments WHERE id=?",id);if(before.isEmpty())throw new IllegalArgumentException("댓글을 찾을 수 없습니다.");
        db.update("DELETE FROM stock_comments WHERE id=?",id);support.audit(actor,"COMMENT_DELETE",id,why,before,Map.of());
    }
    private Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
    private static String optional(String value,int max,String label){return value==null||value.isBlank()?null:text(value,max,label);}
    /** 사이트 안 경로(/...)나 https 주소만 받는다. javascript: 같은 주소가 버튼·그림에 들어가지 않게 한다. */
    private static String link(String value,String label){
        String url=optional(value,500,label);
        if(url!=null&&!url.matches("(?i)https://\\S+|/(?!/)\\S*"))throw new IllegalArgumentException(label+"는 /로 시작하는 경로나 https:// 주소여야 합니다.");
        return url;
    }
}
