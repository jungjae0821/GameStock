package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import static com.gamestock.backend.market.MarketModels.*;

/** Claims are committed before admission. Ambiguous crash outcomes are paused, never replayed. */
@Service
public class QuantScheduler {
    private static final Logger log=LoggerFactory.getLogger(QuantScheduler.class);
    private final JdbcTemplate db;
    private final QuantProjectService projects;
    private final ResearchChartService charts;
    private final OrderService orders;
    private final MarketService market;
    private final TransactionTemplate tx;
    record Candidate(long id,long user) {}
    record Attempt(long run,long project,long user,String code,String side,int quantity,long price) {}
    public QuantScheduler(JdbcTemplate db,QuantProjectService projects,ResearchChartService charts,OrderService orders,
                          MarketService market,PlatformTransactionManager transactionManager) {
        this.db=db;this.projects=projects;this.charts=charts;this.orders=orders;this.market=market;
        this.tx=new TransactionTemplate(transactionManager);
    }
    @Scheduled(fixedDelayString="${gamestock.quant.check-ms:2000}",initialDelayString="${gamestock.quant.initial-delay-ms:45000}")
    public void tick() {
        if(!market.ownerAvailable())return;
        try {
            recover();
            var candidates=db.query("SELECT id,user_id FROM quant_projects WHERE status='ACTIVE' ORDER BY last_checked,id LIMIT 20",
                (r,n)->new Candidate(r.getLong(1),r.getLong(2)));
            Map<String,List<ChartCandle>> cache=new HashMap<>();
            for(Candidate candidate:candidates) {
                try {evaluate(candidate,cache);}
                catch(RuntimeException e) {
                    db.update("UPDATE quant_projects SET status='PAUSED',updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='ACTIVE'",candidate.id());
                    log.warn("Quant project {} paused after evaluation failure",candidate.id(),e);
                }
            }
        } catch(RuntimeException e) {log.warn("Quant scheduler failed",e);}
    }
    void evaluate(Candidate candidate,Map<String,List<ChartCandle>> cache) {
        var project=projects.get(candidate.user(),candidate.id());
        if(!project.status().equals("ACTIVE"))return;
        db.update("UPDATE quant_projects SET last_checked=CURRENT_TIMESTAMP(3) WHERE id=? AND status='ACTIVE'",project.id());
        if(!eligible(project.userId())) {pause(project.id());return;}
        List<ChartCandle> bars=cache.computeIfAbsent(project.stockCode()+":"+project.range(),
            key->charts.candles(project.stockCode(),project.range(),true));
        if(bars.isEmpty())return;
        String bar=bars.get(bars.size()-1).recordedAt();
        if(java.time.Instant.parse(bar).plusSeconds(ResearchChartService.interval(project.range())*2).isBefore(java.time.Instant.now()))return;
        if(bar.equals(project.lastBar()))return;
        var evaluated=QuantCompiler.evaluate(project.program(),bars);
        QuantCompiler.Rule selected=null;
        for(int i=0;i<project.program().rules().size();i++)
            if(QuantCompiler.truth(evaluated.signals().get(i)[bars.size()-1])) {selected=project.program().rules().get(i);break;}
        if(selected==null) {
            db.update("UPDATE quant_projects SET last_bar=? WHERE id=? AND status='ACTIVE' AND revision=? AND (last_bar IS NULL OR last_bar=?)",
                bar,project.id(),project.revision(),project.lastBar());return;
        }
        final QuantCompiler.Rule rule=selected;
        Attempt attempt=tx.execute(status->claim(project,rule,bar));
        if(attempt==null)return;
        try {
            // Admission uses the existing FIFO, balances, account bans and matching protections.
            orders.submit(attempt.user(),new OrderRequest(attempt.code(),attempt.side(),attempt.quantity(),"LIMIT",attempt.price()))
                .whenComplete((result,error)-> {
                    try {finish(attempt,result,error);}catch(RuntimeException e) {log.error("Quant result persistence failed for run {}",attempt.run(),e);}
                });
        } catch(RuntimeException error) {finish(attempt,null,error);}
    }
    private boolean eligible(long user) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT password_hash NOT IN ('BOT','TRADER') AND COALESCE(role,'USER')<>'ADMIN'
              AND (trade_banned_until IS NULL OR trade_banned_until<=CURRENT_TIMESTAMP(3)) FROM users WHERE id=?
            """,Boolean.class,user));
    }
    private void pause(long id) {db.update("UPDATE quant_projects SET status='PAUSED',updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='ACTIVE'",id);}
    private Attempt claim(QuantProjectService.Project expected,QuantCompiler.Rule rule,String bar) {
        var locked=db.query("SELECT id FROM quant_projects WHERE id=? FOR UPDATE",(r,n)->r.getLong(1),expected.id());
        if(locked.isEmpty())return null;
        var current=projects.get(expected.userId(),expected.id());
        if(!current.status().equals("ACTIVE") || current.revision()!=expected.revision() || !Objects.equals(current.lastBar(),expected.lastBar()))return null;
        Long quote=db.queryForObject("SELECT current_price FROM stocks WHERE stock_code=?",Long.class,current.stockCode());
        if(quote==null || quote<=0) {pause(current.id());return null;}
        long price=rule.side().equals("BUY")?PriceLimitPolicy.floorToTick(quote):PriceLimitPolicy.ceilToTick(quote);
        long gross=Math.multiplyExact(price,rule.quantity());
        if(current.ordersUsed()>=current.maxOrders() || gross>current.maxNotional()-current.notionalUsed()) {pause(current.id());return null;}
        db.update("""
            UPDATE quant_projects SET status='RUNNING',last_bar=?,orders_used=orders_used+1,
              notional_used=notional_used+?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?
            """,bar,gross,current.id());
        db.update("INSERT INTO quant_runs(project_id,revision,bar_time,side,quantity,limit_price) VALUES(?,?,?,?,?,?)",
            current.id(),current.revision(),bar,rule.side(),rule.quantity(),price);
        long run=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        return new Attempt(run,current.id(),current.userId(),current.stockCode(),rule.side(),rule.quantity(),price);
    }
    void finish(Attempt attempt,OrderResult result,Throwable error) {
        tx.executeWithoutResult(status->{
            // Same project -> run lock order as recovery and deletion.
            db.queryForObject("SELECT id FROM quant_projects WHERE id=? FOR UPDATE",Long.class,attempt.project());
            String outcome=error==null?"ACCEPTED":"UNKNOWN";
            Throwable cause=error;while(cause!=null && cause.getCause()!=null)cause=cause.getCause();
            if(cause instanceof IllegalArgumentException || (cause instanceof org.springframework.web.server.ResponseStatusException response && response.getStatusCode().is4xxClientError()))outcome="REJECTED";
            String message=error==null?result.status():outcome.equals("REJECTED")?"주문이 거절되었습니다. 계좌와 거래 제한을 확인해 주세요.":"주문 결과를 확인할 수 없습니다. 주문 내역을 확인해 주세요.";
            db.update("UPDATE quant_runs SET status=?,message=?,order_id=? WHERE id=?",
                outcome,message,error==null?result.orderId():null,attempt.run());
            db.update("""
                UPDATE quant_projects SET status=IF(status='PAUSING' OR ?=TRUE OR orders_used>=max_orders OR notional_used>=max_notional,'PAUSED','ACTIVE'),
                  updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status IN ('RUNNING','PAUSING')
                  AND NOT EXISTS (SELECT 1 FROM quant_runs r WHERE r.project_id=? AND r.id>?)
                """,error!=null,attempt.project(),attempt.project(),attempt.run());
        });
    }
    private void recover() {
        tx.executeWithoutResult(status->{
            var stale=db.query("SELECT id FROM quant_projects WHERE status IN ('RUNNING','PAUSING') AND updated_at<CURRENT_TIMESTAMP(3)-INTERVAL 5 MINUTE ORDER BY id LIMIT 20 FOR UPDATE",
                (r,n)->r.getLong(1));
            for(long id:stale) {
                db.update("UPDATE quant_projects SET status='PAUSED',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);
                db.update("UPDATE quant_runs SET status='UNKNOWN',message='처리 중 연결이 끊겼습니다. 주문 내역을 확인한 후 재개해 주세요.' WHERE project_id=? AND status='SUBMITTING'",id);
            }
        });
    }
}
