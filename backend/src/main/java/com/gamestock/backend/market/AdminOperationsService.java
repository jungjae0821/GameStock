package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import java.util.*;
import static com.gamestock.backend.market.AdminSupport.*;

@Service
public class AdminOperationsService {
    private final JdbcTemplate db;
    private final AdminSupport support;
    private final MarketService market;
    private final AdminRuntimeControl runtime;
    private final ApplicationEventPublisher events;
    public AdminOperationsService(JdbcTemplate db,AdminSupport support,MarketService market,AdminRuntimeControl runtime,ApplicationEventPublisher events){this.db=db;this.support=support;this.market=market;this.runtime=runtime;this.events=events;}

    public record RuntimeChange(boolean botsPaused,boolean tradersPaused,boolean marketPaused,Integer cadenceMs,Integer targetPerSymbol,
                                Integer traderRampSeconds,String reason) { }
    public record StockChange(String name,String developer,String genre,String reason) { }
    public record StockCreate(String code,String name,String developer,String genre,long initialPrice,String reason) { }
    public record StockState(String state,String reason) { }
    public record CashChange(String requestKey,long amount,String reason) { }
    public record HoldingChange(String requestKey,String stockCode,int quantity,long averagePrice,String reason) { }
    public record Reason(String reason) { }
    public record Grants(Set<String> permissions,String reason) { }
    public record MissionRecovery(String missionId,java.time.LocalDate rewardedOn,long amount,String reason) { }

    public Map<String,Object> dashboard(){
        Map<String,Object> result=new LinkedHashMap<>();
        // 운영 봇(persona)은 회원 수에 넣지 않는다.
        result.put("users",count("SELECT COUNT(*) FROM users u WHERE "+HUMAN+" AND u.persona IS NULL"));
        result.put("suspendedUsers",count("SELECT COUNT(*) FROM users u WHERE "+HUMAN+" AND u.persona IS NULL AND trade_banned_until>CURRENT_TIMESTAMP"));
        result.put("openOrders",count("SELECT COUNT(*) FROM orders WHERE status='OPEN'"));
        result.put("trades24h",db.queryForMap("SELECT COUNT(*) AS fills,COALESCE(SUM(quantity),0) AS volume,COALESCE(SUM(price*quantity),0) AS notional FROM trades WHERE created_at >= DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY)"));
        result.put("openReports",count("SELECT COUNT(*) FROM user_reports WHERE status='OPEN'"));
        result.put("runtime",runtime.settings());
        result.put("matchingEnabled",market.matchingEnabled());result.put("matchingOwner",market.ownerAvailable());
        result.put("tradeCoverage","retained trades; compact bot-only fills are in market metrics/journal");
        return result;
    }
    public Map<String,Object> user(long user){
        var rows=db.queryForList("SELECT id,nickname,email,google_uid,role,persona,cash,loan_balance,loan_interest_on,created_at,trade_banned_until,trade_ban_reason,account_reset_at FROM users WHERE id=?",user);
        if(rows.isEmpty())throw new IllegalArgumentException("계정을 찾을 수 없습니다.");
        Map<String,Object> result=new LinkedHashMap<>(rows.get(0));
        result.put("portfolio",market.portfolio(user));
        result.put("holdings",db.queryForList("SELECT p.*,s.stock_code,s.current_price FROM portfolios p JOIN stocks s ON s.id=p.stock_id WHERE p.user_id=? ORDER BY s.id",user));
        result.put("loanLedger",db.queryForList("SELECT * FROM loan_ledger WHERE user_id=? ORDER BY id DESC LIMIT 100",user));
        return result;
    }
    public List<Map<String,Object>> orders(Long user,String code,String status,long before,int limit){
        page(limit,before);if(status!=null&&!Set.of("OPEN","FILLED","PARTIAL","CANCELLED").contains(status))throw new IllegalArgumentException("주문 상태를 확인해 주세요.");
        return db.queryForList("SELECT o.*,s.stock_code,u.nickname FROM orders o JOIN stocks s ON s.id=o.stock_id JOIN users u ON u.id=o.user_id WHERE (? IS NULL OR o.user_id=?) AND (? IS NULL OR s.stock_code=?) AND (? IS NULL OR o.status=?) AND (?=0 OR o.id<?) ORDER BY o.id DESC LIMIT ?",user,user,code,code,status,status,before,before,limit);
    }
    public List<Map<String,Object>> trades(Long user,String code,long before,int limit){
        page(limit,before);
        return db.queryForList("SELECT t.*,s.stock_code,st.id AS settlement_id,st.status AS settlement_status FROM trades t JOIN stocks s ON s.id=t.stock_id LEFT JOIN settlements st ON st.trade_id=t.id WHERE (? IS NULL OR t.buyer_id=? OR t.seller_id=?) AND (? IS NULL OR s.stock_code=?) AND (?=0 OR t.id<?) ORDER BY t.id DESC LIMIT ?",user,user,user,code,code,before,before,limit);
    }
    public List<Map<String,Object>> settlements(Long user,String status,long before,int limit){
        page(limit,before);if(status!=null&&!Set.of("PENDING","SETTLED","CANCELLED").contains(status))throw new IllegalArgumentException("정산 상태를 확인해 주세요.");
        return db.queryForList("SELECT st.*,s.stock_code FROM settlements st JOIN stocks s ON s.id=st.stock_id WHERE (? IS NULL OR st.buyer_id=? OR st.seller_id=?) AND (? IS NULL OR st.status=?) AND (?=0 OR st.id<?) ORDER BY st.id DESC LIMIT ?",user,user,user,status,status,before,before,limit);
    }
    @Transactional
    public void settle(long actor,long id,String why){
        reason(why);lock(db);
        var rows=db.queryForList("SELECT * FROM settlements WHERE id=? AND status='PENDING' FOR UPDATE",id);
        if(rows.isEmpty())throw new IllegalArgumentException("처리할 미정산 거래가 없습니다.");
        var row=rows.get(0);long seller=((Number)row.get("seller_id")).longValue(),buyer=((Number)row.get("buyer_id")).longValue();
        long net=((Number)row.get("gross_amount")).longValue()-((Number)row.get("seller_fee")).longValue();
        db.update("UPDATE users SET cash=cash+? WHERE id=?",net,seller);
        db.update("UPDATE portfolios SET settled_quantity=LEAST(quantity,settled_quantity+?) WHERE user_id=? AND stock_id=?",row.get("quantity"),buyer,row.get("stock_id"));
        db.update("UPDATE settlements SET status='SETTLED',settled_at=CURRENT_TIMESTAMP WHERE id=?",id);
        changed();support.audit(actor,"SETTLEMENT_SETTLE",id,why,row,Map.of("status","SETTLED"));
    }
    @Transactional
    public void reverse(long actor,long id,String why){
        reason(why);lock(db);var settings=runtime.settings();
        if(!settings.marketPaused()||!settings.botsPaused())throw new IllegalArgumentException("시장과 봇을 모두 정지한 뒤 취소해 주세요.");
        var rows=db.queryForList("SELECT * FROM settlements WHERE id=? AND status IN ('PENDING','SETTLED') FOR UPDATE",id);
        if(rows.isEmpty())throw new IllegalArgumentException("취소할 정산 거래가 없습니다.");
        var row=rows.get(0);long buyer=((Number)row.get("buyer_id")).longValue(),seller=((Number)row.get("seller_id")).longValue(),stock=((Number)row.get("stock_id")).longValue();
        if(count("SELECT COUNT(*) FROM stock_denominations WHERE stock_id=? AND last_trade_id>=?",stock,row.get("trade_id"))>0)
            throw new IllegalArgumentException("가격 환산 이전 체결은 원래 단위로 자동 취소할 수 없습니다.");
        if(buyer==seller)throw new IllegalArgumentException("자전 거래는 개별 자산 복구로 처리해 주세요.");
        // Compact bot history cannot prove that saved snapshots are still reversible.
        human(db,Math.min(buyer,seller));human(db,Math.max(buyer,seller));
        for(String side:List.of("buyer","seller"))for(String field:List.of("quantity","settled_quantity","average_price","realized_profit_loss"))
            if(row.get(side+"_"+field+"_before")==null)throw new IllegalArgumentException("체결 전 스냅샷이 없어 자동 취소할 수 없습니다.");
        if(count("SELECT COUNT(*) FROM users WHERE id IN (?,?) AND account_reset_at>=?",buyer,seller,row.get("created_at"))>0
                ||count("SELECT COUNT(*) FROM orders WHERE user_id IN (?,?) AND stock_id=? AND status='OPEN'",buyer,seller,stock)>0
                ||count("SELECT COUNT(*) FROM trades t LEFT JOIN settlements st ON st.trade_id=t.id WHERE t.stock_id=? AND t.id>? AND (t.buyer_id IN (?,?) OR t.seller_id IN (?,?)) AND (st.id IS NULL OR st.status<>'CANCELLED')",stock,row.get("trade_id"),buyer,seller,buyer,seller)>0
                ||count("SELECT COUNT(*) FROM admin_audit WHERE action='HOLDING_RESTORE' AND created_at>=?",row.get("created_at"))>0)
            throw new IllegalArgumentException("후속 거래·복구·초기화 또는 미체결 주문이 있어 자동 취소할 수 없습니다.");
        boolean settled="SETTLED".equals(row.get("status"));int quantity=((Number)row.get("quantity")).intValue();
        for(String side:List.of("buyer","seller")){
            long user="buyer".equals(side)?buyer:seller;
            int expected=((Number)row.get(side+"_quantity_before")).intValue()+("buyer".equals(side)?quantity:-quantity);
            int expectedSettled=((Number)row.get(side+"_settled_quantity_before")).intValue()+("buyer".equals(side)?(settled?quantity:0):-quantity);
            var holding=db.queryForList("SELECT quantity,settled_quantity FROM portfolios WHERE user_id=? AND stock_id=? FOR UPDATE",user,stock);
            if(holding.isEmpty()||((Number)holding.get(0).get("quantity")).intValue()!=expected||((Number)holding.get(0).get("settled_quantity")).intValue()!=expectedSettled)
                throw new IllegalArgumentException("현재 보유량이 체결 직후 상태와 달라 자동 취소할 수 없습니다.");
        }
        long gross=((Number)row.get("gross_amount")).longValue(),buyerRefund=Math.addExact(gross,((Number)row.get("buyer_fee")).longValue()),sellerDebit=gross-((Number)row.get("seller_fee")).longValue();
        long buyerCash=db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer);
        long restored=Math.addExact(buyerCash,buyerRefund);
        if(settled&&db.update("UPDATE users SET cash=cash-? WHERE id=? AND cash>=?",sellerDebit,seller,sellerDebit)!=1)throw new IllegalArgumentException("판매자의 사용 가능한 현금이 부족합니다.");
        db.update("UPDATE users SET cash=? WHERE id=?",restored,buyer);
        for(String side:List.of("buyer","seller"))db.update("UPDATE portfolios SET quantity=?,settled_quantity=?,average_price=?,realized_profit_loss=? WHERE user_id=? AND stock_id=?",row.get(side+"_quantity_before"),row.get(side+"_settled_quantity_before"),row.get(side+"_average_price_before"),row.get(side+"_realized_profit_loss_before"),"buyer".equals(side)?buyer:seller,stock);
        db.update("UPDATE settlements SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE id=?",id);
        var executionAt=db.queryForObject("SELECT created_at FROM trades WHERE id=?",java.sql.Timestamp.class,row.get("trade_id"));
        new DerivativePriceOracle(db).invalidate(stock,executionAt.toInstant());
        new PriceMetricService(db).invalidate(stock);db.update("DELETE FROM lp_cash_projection WHERE stock_id=?",stock);
        changed();support.audit(actor,"SETTLEMENT_REVERSE",id,why,row,Map.of("status","CANCELLED","buyerRefund",buyerRefund,"sellerDebit",settled?sellerDebit:0));
    }
    public List<Map<String,Object>> audit(Long actor,String action,long before,int limit){
        page(limit,before);
        return db.queryForList("SELECT * FROM admin_audit WHERE (? IS NULL OR admin_id=?) AND (? IS NULL OR action=?) AND (?=0 OR id<?) ORDER BY id DESC LIMIT ?",actor,actor,action,action,before,before,limit);
    }
    public List<Map<String,Object>> legacyAudit(Long target,long before,int limit){
        page(limit,before);return db.queryForList("SELECT * FROM admin_actions WHERE (? IS NULL OR target_user_id=?) AND (?=0 OR id<?) ORDER BY id DESC LIMIT ?",target,target,before,before,limit);
    }

    @Transactional
    public AdminRuntimeControl.Settings runtime(long actor,RuntimeChange request){
        reason(request.reason());
        if(request.cadenceMs()!=null&&(request.cadenceMs()<50||request.cadenceMs()>10000))throw new IllegalArgumentException("봇 주기는 50~10000ms입니다.");
        if(request.targetPerSymbol()!=null&&(request.targetPerSymbol()<1||request.targetPerSymbol()>1000))throw new IllegalArgumentException("종목당 목표는 1~1000입니다.");
        if(request.traderRampSeconds()!=null&&(request.traderRampSeconds()<0||request.traderRampSeconds()>3600))throw new IllegalArgumentException("트레이더 재가동 시간은 0~3600초입니다.");
        lock(db);var before=runtime.settings();
        boolean tradersStopped=request.botsPaused()||request.tradersPaused();
        db.update("UPDATE admin_runtime SET bots_paused=?,traders_paused=?,market_paused=?,cadence_ms=?,target_per_symbol=?,trader_ramp_seconds=? WHERE id=1",
                request.botsPaused(),request.tradersPaused(),request.marketPaused(),request.cadenceMs(),request.targetPerSymbol(),request.traderRampSeconds());
        // The ramp clock starts only when traders actually come back; a ramp edit while running does not restart it.
        if(before.tradersStopped()&&!tradersStopped)db.update("UPDATE admin_runtime SET traders_resumed_at=CURRENT_TIMESTAMP(3) WHERE id=1");
        // Remove resting quotes of whatever just stopped; a paused bot must not keep filling human orders.
        // A trader-only pause keeps the liquidity provider's quotes so humans can still trade.
        if(request.botsPaused()&&!before.botsPaused())cancelWhere("u.password_hash='BOT'",null);
        if(tradersStopped&&!before.tradersStopped())cancelWhere("u.password_hash='TRADER'",null);
        changed();var after=runtime.settings();support.audit(actor,"RUNTIME",1,request.reason(),before,after);return after;
    }
    @Transactional
    public void cancelOrder(long actor,long order,String why){
        reason(why);lock(db);
        var rows=db.queryForList("SELECT * FROM orders WHERE id=? AND status='OPEN' FOR UPDATE",order);
        if(rows.isEmpty())throw new IllegalArgumentException("취소할 미체결 주문이 없습니다.");
        cancelRows(rows);changed();support.audit(actor,"ORDER_CANCEL",order,why,rows,Map.of("status","CANCELLED"));
    }
    private int cancelWhere(String predicate,Long stock){
        var rows=db.queryForList("SELECT o.* FROM orders o JOIN users u ON u.id=o.user_id WHERE o.status='OPEN' AND "+predicate+" AND (? IS NULL OR o.stock_id=?) ORDER BY o.user_id,o.id FOR UPDATE",stock,stock);
        cancelRows(rows);return rows.size();
    }
    private void cancelRows(List<Map<String,Object>> rows){
        for(var row:rows){long user=((Number)row.get("user_id")).longValue();long reserved=((Number)row.get("reserved_cash")).longValue();
            if(reserved>0)db.update("UPDATE users SET cash=cash+? WHERE id=?",reserved,user);
            db.update("UPDATE orders SET status='CANCELLED',remaining_quantity=0,reserved_cash=0,reserved_quantity=0 WHERE id=?",row.get("id"));
        }
    }
    public List<Map<String,Object>> stocks(){return db.queryForList("SELECT s.*,g.name,g.developer,g.genre,COALESCE(a.state,'ACTIVE') AS state,a.reason FROM stocks s JOIN games g ON g.id=s.game_id LEFT JOIN admin_stock_state a ON a.stock_id=s.id ORDER BY s.id");}
    @Transactional
    public Map<String,Object> createStock(long actor,StockCreate request){
        String code=text(request.code(),12,"종목 코드").toUpperCase(Locale.ROOT);
        if(!code.matches("[A-Z][A-Z0-9_]{0,11}")||Set.of("NEXA","STAR","MOMO","VOID").contains(code)||request.initialPrice()<100||request.initialPrice()>1_000_000_000||request.initialPrice()%PriceLimitPolicy.tickSize(request.initialPrice())!=0)throw new IllegalArgumentException("종목 코드(이전 버전 예약 코드 제외)와 초기 가격·호가 단위를 확인해 주세요.");
        StockChange change=new StockChange(request.name(),request.developer(),request.genre(),request.reason());validateStock(change);lock(db);
        new DerivativeBaskets(db).lock();
        if(count("SELECT COUNT(*) FROM stocks WHERE stock_code=?",code)>0)throw new IllegalArgumentException("이미 존재하는 종목 코드입니다.");
        db.update("INSERT INTO games(name,developer,genre) VALUES(?,?,?)",request.name().trim(),request.developer().trim(),request.genre().trim());
        long game=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO stocks(game_id,stock_code,current_price,previous_price,total_volume) VALUES(?,?,?,?,0)",game,code,request.initialPrice(),request.initialPrice());
        long stock=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO market_book_revisions(stock_id,revision) VALUES(?,0)",stock);
        db.update("INSERT INTO market_metric_cursors(stock_id,last_id) VALUES(?,0)",stock);
        db.update("INSERT INTO stock_price_history(stock_id,price) VALUES(?,?)",stock,request.initialPrice());
        db.update("INSERT INTO stock_protection_state(stock_id,day_reference,static_reference,dynamic_reference,last_trade_price) VALUES(?,?,?,?,?)",stock,request.initialPrice(),request.initialPrice(),request.initialPrice(),request.initialPrice());
        db.update("INSERT INTO market_price_metrics(stock_id,mark_price,hidden_fundamental,anchor_price) VALUES(?,?,?,?)",stock,request.initialPrice(),(double)request.initialPrice(),request.initialPrice());
        new DerivativeBaskets(db).rebalance();
        var after=stock(code);changed();support.audit(actor,"STOCK_CREATE",code,request.reason(),Map.of(),after);return after;
    }
    @Transactional
    public Map<String,Object> updateStock(long actor,String code,StockChange request){
        validateStock(request);lock(db);new DerivativeBaskets(db).lock();var before=stock(code);
        db.update("UPDATE games SET name=?,developer=?,genre=? WHERE id=?",request.name().trim(),request.developer().trim(),request.genre().trim(),before.get("game_id"));
        db.update("INSERT INTO admin_stock_metadata(stock_id,name,developer,genre) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),developer=VALUES(developer),genre=VALUES(genre)",before.get("id"),request.name().trim(),request.developer().trim(),request.genre().trim());
        new DerivativeBaskets(db).rebalance();
        var after=stock(code);support.audit(actor,"STOCK_UPDATE",code,request.reason(),before,after);changed();return after;
    }
    private void validateStock(StockChange r){text(r.name(),100,"종목명");text(r.developer(),100,"개발사");text(r.genre(),50,"장르");reason(r.reason());}
    private Map<String,Object> stock(String code){var rows=db.queryForList("SELECT s.*,g.name,g.developer,g.genre,COALESCE(a.state,'ACTIVE') AS state FROM stocks s JOIN games g ON g.id=s.game_id LEFT JOIN admin_stock_state a ON a.stock_id=s.id WHERE s.stock_code=?",code);if(rows.isEmpty())throw new IllegalArgumentException("종목을 찾을 수 없습니다.");return rows.get(0);}
    @Transactional
    public Map<String,Object> stockState(long actor,String code,StockState request){
        reason(request.reason());if(request.state()==null||!Set.of("ACTIVE","HALTED","DELISTED").contains(request.state()))throw new IllegalArgumentException("상태는 ACTIVE, HALTED, DELISTED입니다.");
        lock(db);new DerivativeBaskets(db).lock();var before=stock(code);
        db.update("INSERT INTO admin_stock_state(stock_id,state,reason) VALUES(?,?,?) ON DUPLICATE KEY UPDATE state=VALUES(state),reason=VALUES(reason),updated_at=CURRENT_TIMESTAMP(3)",before.get("id"),request.state(),request.reason());
        if(!"ACTIVE".equals(request.state()))cancelWhere("TRUE",((Number)before.get("id")).longValue());
        new DerivativeBaskets(db).rebalance();
        var after=stock(code);changed();support.audit(actor,"STOCK_STATE",code,request.reason(),before,after);return after;
    }

    @Transactional
    public Map<String,Object> cash(long actor,long user,CashChange request){
        validateCash(request);lock(db);return cashLocked(actor,user,request);
    }
    private void validateCash(CashChange request){requestKey(request.requestKey());reason(request.reason());if(request.amount()==0||request.amount() < -1_000_000_000L||request.amount()>1_000_000_000L)throw new IllegalArgumentException("조정액은 0을 제외한 ±10억원 이내입니다.");}
    Map<String,Object> cashLocked(long actor,long user,CashChange request){
        var previous=db.queryForList("SELECT * FROM admin_cash_adjustments WHERE request_key=?",request.requestKey());
        if(!previous.isEmpty()){
            var row=previous.get(0);if(((Number)row.get("user_id")).longValue()!=user||((Number)row.get("amount")).longValue()!=request.amount()||!Objects.equals(row.get("reason"),request.reason()))throw new IllegalArgumentException("다른 조치에 사용된 중복 방지 키입니다.");
            return Map.of("userId",user,"amount",request.amount(),"applied",false);
        }
        var before=human(db,user);long next=Math.addExact(((Number)before.get("cash")).longValue(),request.amount());
        if(next<0)throw new IllegalArgumentException("사용 가능한 현금보다 많이 차감할 수 없습니다.");
        db.update("INSERT INTO admin_cash_adjustments(request_key,admin_id,user_id,amount,reason) VALUES(?,?,?,?,?)",request.requestKey(),actor,user,request.amount(),request.reason());
        db.update("UPDATE users SET cash=? WHERE id=?",next,user);
        // Money the admin puts in or takes out is not trading: it must not become bonus, promotion gain or a daily loss.
        CareerService.shiftForOutsideCash(db,user,request.amount());changed();
        support.audit(actor,"CASH_ADJUST",user,request.reason(),before,Map.of("cash",next,"amount",request.amount(),"requestKey",request.requestKey()));
        events.publishEvent(new UserMarketEvent(user,"PORTFOLIO_CHANGED",Map.of("cash",next)));
        return Map.of("userId",user,"amount",request.amount(),"cash",next,"applied",true);
    }
    @Transactional
    public Map<String,Object> rewardAll(long actor,CashChange request){
        validateCash(request);if(request.amount()<0)throw new IllegalArgumentException("전체 지급은 양수만 가능합니다.");lock(db);
        // Persist the recipient list on first execution; retries never include later signups.
        String batchKey="bulk:"+text(request.requestKey(),48,"일괄 지급 키");
        var old=db.queryForList("SELECT * FROM admin_audit WHERE action='CASH_BULK' AND target=? ORDER BY id DESC LIMIT 1",batchKey);
        if(!old.isEmpty())throw new IllegalArgumentException("이미 완료된 일괄 지급 키입니다. 감사 기록을 확인해 주세요.");
        var users=db.queryForList("SELECT u.id FROM users u WHERE "+HUMAN+" ORDER BY u.id",Long.class);
        if(users.size()>10000)throw new IllegalArgumentException("일괄 지급은 최대 10000명입니다. 대상을 나눠 지급해 주세요.");
        for(long user:users)cashLocked(actor,user,new CashChange(batchKey+":"+user,request.amount(),request.reason()));
        support.audit(actor,"CASH_BULK",batchKey,request.reason(),Map.of(),Map.of("count",users.size(),"amountPerUser",request.amount()));
        return Map.of("recipients",users.size(),"amountPerUser",request.amount());
    }
    @Transactional
    public Map<String,Object> recoverMission(long actor,long user,MissionRecovery request){
        reason(request.reason());
        if(request.missionId()==null||!MarketService.MISSION_IDS.contains(request.missionId())||request.rewardedOn()==null||request.rewardedOn().isAfter(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))||request.amount()<1||request.amount()>1_000_000)
            throw new IllegalArgumentException("미션 ID·보상일(오늘 또는 과거)·복구 금액(1~100만원)을 확인해 주세요.");
        lock(db);var before=human(db,user);
        // Recovered mission rewards go where today's do: the personal wallet, not trading cash.
        int inserted=db.update("INSERT IGNORE INTO mission_rewards(user_id,mission_id,rewarded_on,reward_cash,wallet_cash) VALUES(?,?,?,0,?)",user,request.missionId(),java.sql.Date.valueOf(request.rewardedOn()),request.amount());
        if(inserted==0)return Map.of("userId",user,"awarded",false,"rewardCash",0);
        PersonalWallet.credit(db,user,"mission:"+user+":"+request.missionId()+":"+request.rewardedOn(),PersonalWallet.MISSION,request.amount(),"미션 보상 복구");
        long wallet=PersonalWallet.balance(db,user);
        support.audit(actor,"MISSION_RECOVERY",user+":"+request.missionId()+":"+request.rewardedOn(),request.reason(),before,Map.of("walletCash",wallet,"rewardCash",request.amount()));
        changed();events.publishEvent(new UserMarketEvent(user,"WALLET_CHANGED",Map.of("walletCash",wallet)));
        return Map.of("userId",user,"awarded",true,"rewardCash",request.amount());
    }
    @Transactional
    public Map<String,Object> holding(long actor,long user,HoldingChange request){
        String key=requestKey(request.requestKey());reason(request.reason());
        if(request.quantity()<0||request.quantity()>1_000_000||request.averagePrice()<0||request.averagePrice()>1_000_000_000||request.quantity()>0&&request.averagePrice()==0)throw new IllegalArgumentException("복구 수량·평균 단가를 확인해 주세요.");
        lock(db);human(db,user);var stock=stock(request.stockCode());long id=((Number)stock.get("id")).longValue();
        // This endpoint restores an absolute holding, without rewriting a counterparty's ledger.
        if(count("SELECT COUNT(*) FROM admin_audit WHERE action='HOLDING_RESTORE' AND target=?",key)>0)throw new IllegalArgumentException("이미 사용된 복구 키입니다.");
        if(count("SELECT COUNT(*) FROM orders WHERE user_id=? AND stock_id=? AND status='OPEN'",user,id)>0||count("SELECT COUNT(*) FROM settlements WHERE stock_id=? AND (buyer_id=? OR seller_id=?) AND status='PENDING'",id,user,user)>0)throw new IllegalArgumentException("미체결 주문과 미정산 거래를 먼저 처리해 주세요.");
        var before=db.queryForList("SELECT * FROM portfolios WHERE user_id=? AND stock_id=?",user,id);
        long confirmedBefore=db.queryForObject("SELECT "+CareerService.CONFIRMED_ASSET+" FROM users u WHERE u.id=?",Long.class,user);
        db.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price,realized_profit_loss) VALUES(?,?,?,?,?,0) ON DUPLICATE KEY UPDATE quantity=VALUES(quantity),settled_quantity=VALUES(settled_quantity),average_price=VALUES(average_price)",user,id,request.quantity(),request.quantity(),request.averagePrice());
        // Restored shares are not trading gain, the same as admin cash.
        CareerService.shiftForOutsideCash(db,user,db.queryForObject("SELECT "+CareerService.CONFIRMED_ASSET+" FROM users u WHERE u.id=?",Long.class,user)-confirmedBefore);
        support.audit(actor,"HOLDING_RESTORE",key,request.reason(),before,Map.of("userId",user,"stockCode",request.stockCode(),"quantity",request.quantity(),"averagePrice",request.averagePrice()));changed();
        return Map.of("userId",user,"stockCode",request.stockCode(),"quantity",request.quantity());
    }
    @Transactional
    public void permissions(long actor,long user,Grants request){
        reason(request.reason());if(request.permissions()==null||!AdminSupport.PERMISSIONS.containsAll(request.permissions()))throw new IllegalArgumentException("지원하지 않는 관리자 권한입니다.");
        lock(db);human(db,user);var before=db.queryForList("SELECT permission FROM admin_permissions WHERE user_id=?",String.class,user);
        db.update("DELETE FROM admin_permissions WHERE user_id=?",user);for(String permission:request.permissions())db.update("INSERT INTO admin_permissions(user_id,permission) VALUES(?,?)",user,permission);
        support.audit(actor,"PERMISSIONS",user,request.reason(),before,request.permissions());
    }
    public List<Map<String,Object>> permissions(){return db.queryForList("SELECT p.user_id,u.nickname,p.permission FROM admin_permissions p JOIN users u ON u.id=p.user_id ORDER BY p.user_id,p.permission");}
    public List<Map<String,Object>> suspicious(int hours,int minimum){
        if(hours<1||hours>168||minimum<2||minimum>10000)throw new IllegalArgumentException("기간은 1~168시간, 최소 체결 수는 2~10000입니다.");
        return db.queryForList("""
            SELECT LEAST(t.buyer_id,t.seller_id) AS user_a,GREATEST(t.buyer_id,t.seller_id) AS user_b,
              COUNT(*) AS fills,SUM(t.quantity) AS quantity,SUM(t.quantity*t.price) AS notional,
              SUM(CASE WHEN t.buyer_id=t.seller_id THEN 1 ELSE 0 END) AS self_trades,
              COUNT(DISTINCT t.buyer_id) AS buyer_directions,MAX(t.created_at) AS last_trade_at
            FROM trades t JOIN users u ON u.id=t.buyer_id JOIN users v ON v.id=t.seller_id
            WHERE t.created_at>=TIMESTAMPADD(HOUR,-?,CURRENT_TIMESTAMP)
              AND u.google_uid IS NOT NULL AND v.google_uid IS NOT NULL
              AND u.password_hash NOT IN ('BOT','TRADER') AND v.password_hash NOT IN ('BOT','TRADER')
            GROUP BY LEAST(t.buyer_id,t.seller_id),GREATEST(t.buyer_id,t.seller_id)
            HAVING COUNT(*)>=? ORDER BY fills DESC LIMIT 100
            """,hours,minimum);
    }
    void changed(){MarketBookCache.invalidate(db,null);events.publishEvent(new MarketChangedEvent());}
    private long count(String sql,Object... args){Long value=db.queryForObject(sql,Long.class,args);return value==null?0:value;}
}
