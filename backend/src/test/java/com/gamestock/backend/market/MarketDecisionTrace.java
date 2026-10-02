package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import static com.gamestock.backend.market.MarketModels.*;
import static org.mockito.Mockito.*;

/** Opt-in test observer. Delegates each decision exactly once and consumes no simulation randomness. */
final class MarketDecisionTrace {
    private final JdbcTemplate db;
    private final String symbol;
    private final long start,from,to;
    private final Map<Long,String> symbols=new HashMap<>();
    private final IdentityHashMap<PriceMetricService.Metrics,String> metricsSymbols=new IdentityHashMap<>();
    private final List<Map<String,Object>> actions=new ArrayList<>(),books=new ArrayList<>();
    private Map<String,Object> current;
    private List<Map<String,Object>> candidates;
    private long now;

    MarketDecisionTrace(JdbcTemplate db,String symbol,long start,long from,long to) {
        this.db=db;this.symbol=symbol;this.start=start;this.from=from;this.to=to;
    }
    MarketService createMarket(TradingProtectionService protection) {
        return new MarketService(event->{},db,new UserFeatureService(db),protection) {
            @Override BotObservation selectBotObservation(BotProfile profile,long userId,List<String> codes) {
                BotObservation result=super.selectBotObservation(profile,userId,codes);
                if(current!=null) {
                    current.put("selected",result.code()); current.put("decision",result.decision());
                    if(symbol.equals(result.code())) {
                        current.put("restingBefore",result.resting());
                        current.put("keepResting",!result.resting().isEmpty() && !result.replace());
                    }
                }
                return result;
            }
            @Override public synchronized OrderResult order(OrderRequest request,long userId) {
                try {
                    OrderResult result=super.order(request,userId);
                    if(current!=null && request.stockCode().equals(symbol)) {
                        current.put("submitted",request);current.put("result",result.status());current.put("message",result.message());
                    }
                    return result;
                } catch(IllegalArgumentException rejected) {
                    if(current!=null && request.stockCode().equals(symbol)) {
                        current.put("submitted",request);current.put("rejected",rejected.getMessage());
                    }
                    throw rejected;
                }
            }
        };
    }
    void install(MarketService market,long seed) {
        db.query("SELECT id,stock_code FROM stocks",rs->{symbols.put(rs.getLong(1),rs.getString(2));});
        PriceMetricService priceMetrics=spy((PriceMetricService)ReflectionTestUtils.getField(market,"priceMetrics"));
        doAnswer(call->{
            PriceMetricService.Metrics result=(PriceMetricService.Metrics)call.callRealMethod();
            if(current!=null) metricsSymbols.put(result,symbols.get(call.<Long>getArgument(0)));
            return result;
        }).when(priceMetrics).read(anyLong(),anyLong(),anyLong(),anyLong());
        ReflectionTestUtils.setField(market,"priceMetrics",priceMetrics);
        BotStrategyEngine strategies=spy((BotStrategyEngine)ReflectionTestUtils.getField(market,"strategies"));
        doAnswer(call->{
                BotProfile p=call.getArgument(0);PriceMetricService.Metrics m=call.getArgument(1);
                MarketEnvironment e=call.getArgument(2);BotStrategyEngine.Position position=call.getArgument(3);
                double estimatedValue=call.getArgument(4),perceivedNews=call.getArgument(5);
                double result=(Double)call.callRealMethod();
                if(current!=null) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("symbol",metricsSymbols.get(m));row.put("strategy",p.strategy());row.put("score",result);
                    row.put("threshold",p.confidenceThreshold()*(1+Math.max(0,m.volatility()*100-p.volatilityTolerance())));
                    row.put("position",position);row.put("estimatedValue",estimatedValue);row.put("news",perceivedNews);
                    row.put("metrics",m);row.put("environment",e);
                    candidates.add(row);
                }
                return result;
        }).when(strategies).score(any(),any(),any(),any(),anyDouble(),anyDouble(),anyDouble());
        doAnswer(call->{
                BotStrategyEngine.Decision decision=(BotStrategyEngine.Decision)call.callRealMethod();
                if(current!=null) {
                    candidates.get(candidates.size()-1).put("decision",decision);
                }
                return decision;
        }).when(strategies).decide(any(),any(),any(),any(),anyDouble(),anyDouble(),anyDouble(),anyDouble());
        ReflectionTestUtils.setField(market,"strategies",strategies);
    }
    void before(String name,long time) {
        now=time;metricsSymbols.clear();current=null;
        if(time-start<from*1000 || time-start>to*1000) return;
        if(!name.startsWith("trader_bot_")) return;
        current=new LinkedHashMap<>();candidates=new ArrayList<>();
        current.put("second",(time-start)/1000.0);current.put("actor",name);current.put("candidates",candidates);
    }
    void after(String name,long time) {
        if(current!=null) actions.add(current);
        if(time-start>=from*1000 && time-start<=to*1000 && name.equals("liquidity:"+symbol)) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("second",(time-start)/1000.0);
            row.put("book",db.queryForList("SELECT o.side,o.price,SUM(o.remaining_quantity) quantity FROM orders o JOIN stocks s ON s.id=o.stock_id WHERE s.stock_code=? AND o.status='OPEN' AND (o.expires_at IS NULL OR o.expires_at>CURRENT_TIMESTAMP) GROUP BY o.side,o.price ORDER BY o.side,o.price",symbol));
            books.add(row);
        }
        current=null;metricsSymbols.clear();
    }
    Map<String,Object> report(long seed) {
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("symbol",symbol);report.put("from",from);report.put("to",to);report.put("profiles",BotProfile.defaults(seed));
        report.put("actions",actions);report.put("books",books);
        report.put("trades",db.queryForList("SELECT t.id,UNIX_TIMESTAMP(t.created_at)-? second,t.price,t.quantity,bu.username buyer,su.username seller,t.buy_order_id,t.sell_order_id FROM trades t JOIN stocks s ON s.id=t.stock_id JOIN users bu ON bu.id=t.buyer_id JOIN users su ON su.id=t.seller_id WHERE s.stock_code=? ORDER BY t.id",start/1000,symbol));
        report.put("orders",db.queryForList("SELECT o.id,u.username,UNIX_TIMESTAMP(o.created_at)-? second,o.side,o.order_type,o.price,o.quantity,o.status,o.remaining_quantity,UNIX_TIMESTAMP(o.expires_at)-? expires FROM orders o JOIN users u ON u.id=o.user_id JOIN stocks s ON s.id=o.stock_id WHERE s.stock_code=? AND u.password_hash='TRADER' ORDER BY o.id",start/1000,start/1000,symbol));
        report.put("holdings",db.queryForList("SELECT u.username,p.quantity,p.average_price,u.cash FROM portfolios p JOIN users u ON u.id=p.user_id JOIN stocks s ON s.id=p.stock_id WHERE s.stock_code=?",symbol));
        return report;
    }
}
