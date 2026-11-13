package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.gamestock.backend.market.AdminSupport.*;

/** Explicit seasons coexist with the existing automatically awarded weekly ranking. */
@Service
public class AdminSeasonService {
    private final JdbcTemplate db;
    private final AdminSupport support;
    private final AdminOperationsService operations;
    private final AdminRuntimeControl runtime;
    public AdminSeasonService(JdbcTemplate db,AdminSupport support,AdminOperationsService operations,AdminRuntimeControl runtime){this.db=db;this.support=support;this.operations=operations;this.runtime=runtime;}
    public record Create(String name,String reason) { }
    public record Close(List<Long> rankRewards,String reason) { }

    public List<Map<String,Object>> seasons(){return db.queryForList("SELECT * FROM admin_seasons ORDER BY id DESC LIMIT 100");}
    public List<Map<String,Object>> ranking(long id,int limit){page(limit,0);return db.queryForList("SELECT * FROM admin_season_entries WHERE season_id=? ORDER BY rank_no,user_id LIMIT ?",id,limit);}
    @Transactional
    public Map<String,Object> create(long actor,Create request){
        String name=text(request.name(),100,"시즌 이름");reason(request.reason());lock(db);
        db.update("INSERT INTO admin_seasons(name) VALUES(?)",name);long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        support.audit(actor,"SEASON_CREATE",id,request.reason(),Map.of(),Map.of("name",name,"status","DRAFT"));return Map.of("id",id,"name",name,"status","DRAFT");
    }
    @Transactional
    public void start(long actor,long id,String why){
        reason(why);lock(db);var before=season(id,"DRAFT");
        if(db.queryForObject("SELECT COUNT(*) FROM admin_seasons WHERE status='ACTIVE'",Long.class)>0)throw new IllegalArgumentException("진행 중인 시즌을 먼저 종료해 주세요.");
        var assets=assets();for(var user:assets)db.update("INSERT INTO admin_season_entries(season_id,user_id,nickname,opening_asset) VALUES(?,?,?,?)",id,user.get("id"),user.get("nickname"),user.get("asset"));
        db.update("UPDATE admin_seasons SET status='ACTIVE',started_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);
        support.audit(actor,"SEASON_START",id,why,before,Map.of("participants",assets.size()));
    }
    @Transactional
    public List<Map<String,Object>> close(long actor,long id,Close request){
        reason(request.reason());if(request.rankRewards()==null||request.rankRewards().size()>100||request.rankRewards().stream().anyMatch(x->x==null||x<0||x>1_000_000_000L))throw new IllegalArgumentException("1~100위 보상은 각각 0~10억원입니다.");
        lock(db);var before=season(id,"ACTIVE");
        // A closing snapshot is taken under the same exclusive gate as matching.
        for(var user:assets()){
            db.update("INSERT IGNORE INTO admin_season_entries(season_id,user_id,nickname,opening_asset) VALUES(?,?,?,1000000)",id,user.get("id"),user.get("nickname"));
            db.update("UPDATE admin_season_entries SET closing_asset=?,nickname=? WHERE season_id=? AND user_id=?",user.get("asset"),user.get("nickname"),id,user.get("id"));
        }
        // Rank by gain with outside cash adjustments removed. Ties use account ID.
        var entries=db.queryForList("""
            SELECT e.*,COALESCE((SELECT SUM(c.amount) FROM admin_cash_adjustments c
              WHERE c.user_id=e.user_id AND c.created_at>=s.started_at),0) AS external_cash
            FROM admin_season_entries e JOIN admin_seasons s ON s.id=e.season_id
            WHERE e.season_id=? AND e.closing_asset IS NOT NULL
            """,id);
        entries.sort(Comparator.<Map<String,Object>>comparingLong(e->((Number)e.get("closing_asset")).longValue()-((Number)e.get("opening_asset")).longValue()-((Number)e.get("external_cash")).longValue()).reversed().thenComparingLong(e->((Number)e.get("user_id")).longValue()));
        int rank=0;for(var e:entries){rank++;long reward=rank<=request.rankRewards().size()?request.rankRewards().get(rank-1):0;
            db.update("UPDATE admin_season_entries SET rank_no=?,reward_cash=? WHERE season_id=? AND user_id=?",rank,reward,id,e.get("user_id"));
            if(reward>0)PersonalWallet.credit(db,((Number)e.get("user_id")).longValue(),"season:"+id+":"+e.get("user_id"),PersonalWallet.SEASON,reward,"시즌 "+id+" "+rank+"위 보상");
            // Season titles stack like the weekly ones: 1st gets 1·2·3, 2nd gets 2·3, 3rd gets 3.
            long winner=((Number)e.get("user_id")).longValue();
            for(int place=3;place>=rank&&place>=1;place--)awardSeasonTitle(winner,"season_"+place);
        }
        db.update("UPDATE admin_seasons SET status='CLOSED',ended_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);
        support.audit(actor,"SEASON_CLOSE",id,request.reason(),before,Map.of("participants",entries.size(),"rankRewards",request.rankRewards()));return ranking(id,100);
    }
    @Transactional
    public void reset(long actor,long id,String why){
        reason(why);lock(db);var before=season(id,"CLOSED");var settings=runtime.settings();
        if(!settings.marketPaused()||!settings.botsPaused())throw new IllegalArgumentException("시장과 봇을 모두 정지한 뒤 초기화해 주세요.");
        if(db.queryForObject("SELECT COUNT(*) FROM admin_seasons WHERE status='ACTIVE'",Long.class)>0)throw new IllegalArgumentException("진행 중인 시즌이 있으면 초기화할 수 없습니다.");
        if(db.queryForObject("SELECT COUNT(*) FROM settlements WHERE status='PENDING'",Long.class)>0)throw new IllegalArgumentException("미정산 거래를 먼저 처리해 주세요.");
        var users=db.queryForList("SELECT u.id FROM users u WHERE "+HUMAN+" ORDER BY u.id",Long.class);
        if(users.size()>10000)throw new IllegalArgumentException("초기화는 최대 10000명입니다.");
        for(long user:users){
            var state=human(db,user);var holdings=db.queryForList("SELECT * FROM portfolios WHERE user_id=?",user);var orders=db.queryForList("SELECT * FROM orders WHERE user_id=? AND status='OPEN'",user);
            // Preserve the exact pre-reset state in the immutable audit before clearing assets.
            var derivatives=db.queryForList("SELECT * FROM derivative_positions WHERE user_id=? AND status='OPEN'",user);
            var attendance=db.queryForList("SELECT * FROM attendance_rewards WHERE user_id=?",user);var missions=db.queryForList("SELECT * FROM mission_rewards WHERE user_id=?",user);
            support.audit(actor,"SEASON_USER_RESET",id+":"+user,why,Map.of("user",state,"holdings",holdings,"orders",orders,"attendance",attendance,"missions",missions,"derivatives",derivatives),Map.of("cash",1000000,"loanBalance",0));
            db.update("UPDATE orders SET status='CANCELLED',remaining_quantity=0,reserved_cash=0,reserved_quantity=0 WHERE user_id=? AND status='OPEN'",user);
            db.update("UPDATE derivative_positions SET status='RESET',quantity=0,margin=0,mark_value=0,closed_at=CURRENT_TIMESTAMP(3),close_reason='SEASON_RESET' WHERE user_id=? AND status='OPEN'",user);
            db.update("DELETE FROM portfolios WHERE user_id=?",user);
            db.update("DELETE FROM attendance_rewards WHERE user_id=?",user);db.update("DELETE FROM mission_rewards WHERE user_id=?",user);
            db.update("INSERT INTO admin_cash_epochs(user_id,last_adjustment_id) SELECT ?,COALESCE(MAX(id),0) FROM admin_cash_adjustments WHERE user_id=? ON DUPLICATE KEY UPDATE last_adjustment_id=VALUES(last_adjustment_id)",user,user);
            db.update("UPDATE users SET cash=1000000,loan_balance=0,loan_interest_on=NULL,account_reset_at=CURRENT_TIMESTAMP(3) WHERE id=?",user);
            // The grade stays; its base and high-water mark restart from the reset cash so the reset is not read as a loss.
            CareerService.rebase(db,user,1000000);
        }
        db.update("UPDATE admin_seasons SET status='RESET' WHERE id=?",id);operations.changed();
        support.audit(actor,"SEASON_RESET",id,why,before,Map.of("users",users.size(),"status","RESET"));
    }
    private void awardSeasonTitle(long userId,String titleId){
        db.update("""
            INSERT INTO user_titles (user_id, title_id, win_count, notified, acquired_at, updated_at)
            VALUES (?, ?, 1, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE win_count = win_count + 1, notified = FALSE, updated_at = CURRENT_TIMESTAMP
            """,userId,titleId);
    }
    private Map<String,Object> season(long id,String status){var rows=db.queryForList("SELECT * FROM admin_seasons WHERE id=? AND status=? FOR UPDATE",id,status);if(rows.isEmpty())throw new IllegalArgumentException("시즌이 없거나 현재 상태에서 실행할 수 없습니다.");return rows.get(0);}
    private List<Map<String,Object>> assets(){return db.queryForList("SELECT u.id,u.nickname,("
        +AccountValuation.GROSS_ASSET+"-u.loan_balance) AS asset FROM users u WHERE "+HUMAN+" ORDER BY u.id");}
}
