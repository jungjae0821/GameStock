package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.MarketModels.*;

/** Opt-in, accelerated event-time run of the real SQL order/settlement engine, on an isolated DB only. */
@EnabledIfSystemProperty(named="market.soak",matches="true")
class MarketSoakTest {
    private static final class SimulationClock extends Clock {
        long millis;
        SimulationClock(long millis) { this.millis=millis; }
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
        @Override public Instant instant(){return Instant.ofEpochMilli(millis);}
        @Override public long millis(){return millis;}
    }
    private record Action(long time,String name) {}
    private final ObjectMapper json=new ObjectMapper();

    @Test void runEventTimeScenarios() throws Exception {
        String url=System.getProperty("market.test.jdbc","");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13367/"),"Use the dedicated disposable MySQL port");
        String[] seeds=System.getProperty("market.soak.seeds","11,29,73").split(",");
        boolean compareBaseline=Boolean.getBoolean("market.soak.compareBaseline");
        int seconds=Integer.getInteger("market.soak.seconds",7200);
        String label=System.getProperty("market.soak.label","run");
        boolean news=Boolean.parseBoolean(System.getProperty("market.soak.news","true"));
        ExecutorService workers=Executors.newFixedThreadPool(Math.min(seeds.length+(compareBaseline?1:0),Integer.getInteger("market.soak.workers",1)));
        try {
            List<Future<?>> runs=new ArrayList<>();
            for(String seed:seeds) runs.add(workers.submit(()->{
                try {run(url,Long.parseLong(seed.trim()),seconds,news,label);}
                catch(Exception error){throw new CompletionException(error);}
            }));
            if(compareBaseline) runs.add(workers.submit(()->{
                try {run(url,Long.parseLong(seeds[0].trim()),seconds,false,label+"-no-news");}
                catch(Exception error){throw new CompletionException(error);}
            }));
            List<Throwable> errors=new ArrayList<>();
            for(Future<?> run:runs) try {run.get();} catch(ExecutionException error){errors.add(error.getCause());}
            assertTrue(errors.isEmpty(),errors.toString());
        } finally {workers.shutdownNow();}
    }

    private void run(String url,long seed,int seconds,boolean newsShock,String label) throws Exception {
        long wallStart=System.nanoTime();
        String database="soak_test_"+UUID.randomUUID().toString().replace("-","");
        Path folder=Path.of("target","market-soak",label);
        Files.createDirectories(folder);
        List<Map<String,Object>> samples=new ArrayList<>();
        Map<String,Object> report=new LinkedHashMap<>();
        List<String> failures=new ArrayList<>();
        String utcUrl=url+(url.contains("?")?"&":"?")+"connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        try(SingleConnectionDataSource ds=new SingleConnectionDataSource(utcUrl,"root","",true)) {
            JdbcTemplate db=new JdbcTemplate(ds);
            db.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            db.execute("USE "+database);
            db.execute("SET time_zone='+00:00'");
            long start=Instant.parse("2026-10-02T00:00:00Z").toEpochMilli();
            SimulationClock clock=new SimulationClock(start);
            db.execute("SET timestamp="+start/1000);
            assertEquals(clock.instant(),db.queryForObject("SELECT CURRENT_TIMESTAMP",java.sql.Timestamp.class).toInstant());
            assertEquals(clock.instant(),db.queryForObject("SELECT CAST(? AS DATETIME)",java.sql.Timestamp.class,
                    java.sql.Timestamp.from(clock.instant())).toInstant());
            try {
                String schema=Files.readString(Path.of("../database/schema.sql"))
                        .replaceAll("(?m)^--.*$","").replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
                for(String statement:schema.split(";")) if(!statement.isBlank()) db.execute(statement);
                TradingProtectionService protection=new TradingProtectionService(db);
                ReflectionTestUtils.setField(protection,"clock",clock);
                // Socket delivery is independently exercised in MarketSocketHandlerTest. The complete
                // production MarketService runs here, including all order, risk, expiry and ledger paths.
                MarketDecisionTrace trace=Boolean.getBoolean("market.soak.trace")?new MarketDecisionTrace(db,
                        System.getProperty("market.soak.trace.symbol","ES"),start,
                        Long.getLong("market.soak.trace.from",780L),Long.getLong("market.soak.trace.to",1500L)):null;
                MarketService market=trace==null?new MarketService(event->{},db,new UserFeatureService(db),protection):trace.createMarket(protection);
                ReflectionTestUtils.setField(market,"clock",clock);
                ReflectionTestUtils.setField(market,"configuredSeed",Long.toString(seed));
                market.initializeData();
                if(trace!=null) trace.install(market,seed);
                TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
                List<String> symbols=market.botStockCodes();
                long initialCash=number(db,"SELECT SUM(cash) FROM users");
                long initialShares=number(db,"SELECT SUM(quantity) FROM portfolios");
                PriorityQueue<Action> queue=new PriorityQueue<>(Comparator.comparingLong(Action::time).thenComparing(Action::name));
                queue.add(new Action(start,"maintain")); queue.add(new Action(start,"sample"));
                if(newsShock) {
                    queue.add(new Action(start+seconds*1000L/3,"news_up"));
                    queue.add(new Action(start+seconds*2000L/3,"news_down"));
                }
                for(String symbol:symbols) queue.add(new Action(start+market.nextBotDelay("liquidity:"+symbol),"liquidity:"+symbol));
                for(String bot:market.participantBotUsernames()) queue.add(new Action(start+market.nextBotDelay(bot),bot));
                long actions=0;
                while(!queue.isEmpty() && queue.peek().time<=start+seconds*1000L) {
                    Action action=queue.remove(); clock.millis=action.time;
                    // CURRENT_TIMESTAMP/DATE_SUB/expiry and Java indicators share exactly the same event clock.
                    db.execute("SET timestamp="+String.format(Locale.ROOT,"%.3f",clock.millis/1000.0));
                    if(action.name.equals("sample")) {
                        Map<String,Object> sample=sample(db,market,protection,(clock.millis-start)/1000,initialCash,initialShares,failures);
                        samples.add(sample);
                        if((clock.millis-start)%300000==0) {
                            System.out.printf(Locale.ROOT,"SOAK %s seed=%d t=%ds actions=%d fills=%s shares=%s elapsed=%.1fs%n",label,seed,
                                    (clock.millis-start)/1000,actions,sample.get("trades"),sample.get("volume"),(System.nanoTime()-wallStart)/1e9);
                            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("seed-"+seed+"-samples.json").toFile(),samples);
                        }
                        queue.add(new Action(action.time+60000,"sample"));
                    } else if(action.name.startsWith("news_")) {
                        boolean up=action.name.equals("news_up");
                        for(Stock stock:market.stocks()) db.update("INSERT INTO market_events(stock_id,event_type,title,description,impact,source) SELECT id,'NEWS',?,?,?,'soak' FROM stocks WHERE stock_code=?",
                                stock.name()+(up?" 대규모 업데이트 흥행":" 서비스 장애 논란"),stock.name()+(up?" 매출 증가":" 서비스 장애 발생"),up?7:-9,stock.code());
                    } else {
                        if(trace!=null) trace.before(action.name,clock.millis);
                        tx.executeWithoutResult(status->{
                            if(action.name.equals("maintain")) market.maintainMarket();
                            else if(action.name.startsWith("liquidity:")) market.liquidityBotAction(action.name.substring(10),"BOTH");
                            else market.participantBotAction(action.name);
                        });
                        if(trace!=null) trace.after(action.name,clock.millis);
                        actions++;
                        queue.add(new Action(action.time+(action.name.equals("maintain")?3000:market.nextBotDelay(action.name)),action.name));
                    }
                }
                report.put("seed",seed); report.put("simulatedSeconds",seconds); report.put("actions",actions);
                report.put("newsShock",newsShock);
                // Exact intervals include initial warmup and the terminal quiet period, not just minute samples.
                List<Map<String,Object>> gaps=new ArrayList<>();
                for(String symbol:symbols) {
                    List<Long> times=db.query("SELECT t.created_at FROM trades t JOIN stocks s ON s.id=t.stock_id WHERE s.stock_code=? ORDER BY t.created_at,t.id",
                            (rs,n)->rs.getTimestamp(1).getTime(),symbol);
                    long previous=start,maxGap=0; int overFiveMinutes=0;
                    times.add(start+seconds*1000L);
                    for(long time:times) {
                        long gap=time-previous;
                        maxGap=Math.max(maxGap,gap); if(gap>=300000) overFiveMinutes++;
                        previous=time;
                    }
                    gaps.add(Map.of("symbol",symbol,"longestSeconds",maxGap/1000.0,"overFiveMinutes",overFiveMinutes));
                }
                report.put("tradeGaps",gaps);
                report.put("wallSeconds",(System.nanoTime()-wallStart)/1e9);
                report.put("samples",samples); report.put("invariantFailures",failures);
                report.put("participants",db.queryForList("""
                        SELECT u.username,u.nickname,u.cash,
                          COALESCE((SELECT SUM(reserved_cash) FROM orders WHERE user_id=u.id AND status='OPEN'),0) reserved_cash,
                          COALESCE((SELECT SUM(p.quantity*pm.mark_price) FROM portfolios p JOIN market_price_metrics pm ON pm.stock_id=p.stock_id WHERE p.user_id=u.id),0) assets,
                          COALESCE((SELECT SUM(quantity) FROM trades WHERE buyer_id=u.id),0) buys,
                          COALESCE((SELECT SUM(quantity) FROM trades WHERE seller_id=u.id),0) sells
                        FROM users u WHERE u.password_hash IN ('BOT','TRADER') ORDER BY u.username
                        """));
                report.put("symbols",db.queryForList("""
                        SELECT s.stock_code,s.current_price,pm.mark_price,p.quantity lp_inventory,r.opening_cash,
                          COALESCE((SELECT SUM(quantity) FROM trades WHERE stock_id=s.id),0) volume,
                          COALESCE((SELECT COUNT(*) FROM trades WHERE stock_id=s.id),0) fills,
                          COALESCE((SELECT MAX(price) FROM trades WHERE stock_id=s.id),s.current_price) high,
                          COALESCE((SELECT MIN(price) FROM trades WHERE stock_id=s.id),s.current_price) low
                        FROM stocks s JOIN market_price_metrics pm ON pm.stock_id=s.id JOIN lp_risk_books r ON r.stock_id=s.id
                        JOIN portfolios p ON p.stock_id=s.id JOIN users u ON u.id=p.user_id AND u.username='liquidity_provider'
                        ORDER BY s.stock_code
                        """));
                json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("seed-"+seed+".json").toFile(),report);
                if(trace!=null) json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("trace-"+seed+".json").toFile(),trace.report(seed));
                assertTrue(failures.isEmpty(),failures.toString());
            } finally {
                db.execute("SET timestamp=0");
                db.execute("DROP DATABASE "+database);
            }
        }
    }

    private Map<String,Object> sample(JdbcTemplate db,MarketService market,TradingProtectionService protection,
                                      long elapsed,long initialCash,long initialShares,List<String> failures) {
        Map<String,Object> row=new LinkedHashMap<>();
        long trades=number(db,"SELECT COUNT(*) FROM trades"),volume=number(db,"SELECT COALESCE(SUM(quantity),0) FROM trades");
        long cash=number(db,"SELECT SUM(cash) FROM users"),reserved=number(db,"SELECT COALESCE(SUM(reserved_cash),0) FROM orders WHERE status='OPEN'");
        long fees=number(db,"SELECT COALESCE(SUM(buyer_fee+seller_fee),0) FROM trades");
        long shares=number(db,"SELECT SUM(quantity) FROM portfolios");
        long negatives=number(db,"SELECT (SELECT COUNT(*) FROM users WHERE cash<0)+(SELECT COUNT(*) FROM portfolios WHERE quantity<0 OR settled_quantity<0)");
        long self=number(db,"SELECT COUNT(*) FROM trades WHERE buyer_id=seller_id");
        if(cash+reserved+fees!=initialCash) failures.add("t="+elapsed+" cash conservation delta="+(cash+reserved+fees-initialCash));
        if(shares!=initialShares || negatives>0 || self>0) failures.add("t="+elapsed+" shares/negative/self="+shares+"/"+negatives+"/"+self);
        row.put("second",elapsed); row.put("trades",trades); row.put("volume",volume); row.put("fees",fees);
        row.put("cashConservationDelta",cash+reserved+fees-initialCash); row.put("totalShares",shares);
        row.put("openOrders",number(db,"SELECT COUNT(*) FROM orders WHERE status='OPEN'"));
        row.put("marketOpen",protection.marketStatus().open());
        row.put("regime",((MarketRegimeEngine)ReflectionTestUtils.getField(market,"regimes")).regime().name());
        PatternEngine patterns=(PatternEngine)ReflectionTestUtils.getField(market,"patterns");
        Map<String,String> patternNames=new TreeMap<>();
        for(String symbol:market.botStockCodes()) patternNames.put(symbol,patterns.current(symbol).name());
        row.put("patterns",patternNames);
        row.put("books",db.queryForList("""
                SELECT s.stock_code,s.current_price,
                  COALESCE(SUM(CASE WHEN o.side='BUY' THEN o.remaining_quantity ELSE 0 END),0) bids,
                  COALESCE(SUM(CASE WHEN o.side='SELL' THEN o.remaining_quantity ELSE 0 END),0) asks,
                  MAX(CASE WHEN o.side='BUY' THEN o.price END) best_bid,
                  MIN(CASE WHEN o.side='SELL' THEN o.price END) best_ask,
                  (SELECT TIMESTAMPDIFF(SECOND,MAX(created_at),CURRENT_TIMESTAMP) FROM trades WHERE stock_id=s.id) stale_seconds,
                  (SELECT quantity FROM portfolios p JOIN users u ON u.id=p.user_id WHERE p.stock_id=s.id AND u.username='liquidity_provider') lp_inventory,
                  (SELECT vi_type FROM stock_protection_state WHERE stock_id=s.id) vi
                FROM stocks s LEFT JOIN orders o ON o.stock_id=s.id AND o.status='OPEN'
                  AND (o.expires_at IS NULL OR o.expires_at>CURRENT_TIMESTAMP)
                GROUP BY s.id ORDER BY s.stock_code
                """));
        return row;
    }
    private static long number(JdbcTemplate db,String sql){return db.queryForObject(sql,Long.class);}
}
