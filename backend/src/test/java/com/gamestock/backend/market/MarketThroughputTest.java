package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.MarketModels.*;

/** Real wall-clock, committed MySQL fills. Opt-in; never reads the application's datasource. */
@EnabledIfSystemProperty(named="market.throughput", matches="true")
class MarketThroughputTest {
    @Test void committedMatchingThroughput() throws Exception {
        String url=System.getProperty("market.test.jdbc", "");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13367/"), "disposable test DB only");
        String schema="throughput_test_"+UUID.randomUUID().toString().replace("-", "");
        HikariConfig adminConfig=new HikariConfig();
        adminConfig.setJdbcUrl(url); adminConfig.setUsername("root"); adminConfig.setPassword("");
        adminConfig.setMaximumPoolSize(1);
        try(HikariDataSource admin=new HikariDataSource(adminConfig)) {
            JdbcTemplate root=new JdbcTemplate(admin);
            root.execute("CREATE DATABASE "+schema);
            try { run(url,schema); } finally { root.execute("DROP DATABASE "+schema); }
        }
    }

    private void run(String url,String schema) throws Exception {
        int workers=Integer.getInteger("market.throughput.workers",8);
        int seconds=Integer.getInteger("market.throughput.seconds",20);
        HikariConfig config=new HikariConfig();
        config.setJdbcUrl(url.replace("/?","/"+schema+"?")+"&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"); config.setUsername("root"); config.setPassword("");
        config.setMaximumPoolSize(workers+2); config.setConnectionInitSql("SET time_zone='+00:00'");
        try(HikariDataSource source=new HikariDataSource(config)) {
            JdbcTemplate db=new JdbcTemplate(source);
            assertTrue(Math.abs(db.queryForObject("SELECT CURRENT_TIMESTAMP",java.sql.Timestamp.class).getTime()-System.currentTimeMillis())<2000,"SQL and Java must share the production UTC clock");
            String ddl=Files.readString(Path.of("../database/schema.sql"))
                    .replaceAll("(?m)^--.*$", "").replaceAll("(?is)CREATE DATABASE[^;]+;", "").replaceAll("(?is)USE gamestock;", "");
            for(String statement:ddl.split(";")) if(!statement.isBlank()) db.execute(statement);
            MarketService market=new MarketService(e->{},db,new UserFeatureService(db),new TradingProtectionService(db));
            ReflectionTestUtils.setField(market,"configuredSeed","12345");
            market.initializeData();
            TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.executeWithoutResult(s->market.maintainMarket());
            var codes=market.botStockCodes();
            List<long[]> accounts=new ArrayList<>();
            for(int i=0;i<workers;i++) {
                long[] pair=new long[2];
                for(int j=0;j<2;j++) {
                    String name="load_"+i+"_"+j;
                    db.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES (?,'BOT',?,100000000)",name,name);
                    pair[j]=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
                    for(String code:codes) db.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price) SELECT ?,id,1000,1000,current_price FROM stocks WHERE stock_code=?",pair[j],code);
                }
                accounts.add(pair);
            }
            long cashBefore=db.queryForObject("SELECT SUM(cash) FROM users",Long.class);
            long sharesBefore=db.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class);
            AtomicLong fills=new AtomicLong(), submitted=new AtomicLong(), cancelledMarkets=new AtomicLong();
            ConcurrentLinkedQueue<Long> latencies=new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Throwable> failures=new ConcurrentLinkedQueue<>();
            boolean background=Boolean.getBoolean("market.throughput.background");
            ScheduledThreadPoolExecutor scheduled=new ScheduledThreadPoolExecutor(3);
            scheduled.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            CountDownLatch ready=new CountDownLatch(workers),start=new CountDownLatch(1);
            AtomicLong deadline=new AtomicLong();
            ExecutorService pool=Executors.newFixedThreadPool(workers);
            List<Future<?>> jobs=new ArrayList<>();
            for(int i=0;i<workers;i++) {
                final int worker=i;
                jobs.add(pool.submit(()-> {
                    ready.countDown();
                    try {
                        start.await(); long turn=0;
                        while(System.nanoTime()<deadline.get()) {
                            String code=codes.get(worker%codes.size()); long[] pair=accounts.get(worker);
                            long seller=pair[(int)(turn%2)],buyer=pair[(int)((turn+1)%2)];
                            long began=System.nanoTime();
                            tx.executeWithoutResult(s->market.order(new OrderRequest(code,"SELL",1,"LIMIT",10000L),seller));
                            submitted.incrementAndGet();
                            var result=tx.execute(s->market.order(new OrderRequest(code,"BUY",1,"MARKET",null),buyer));
                            submitted.incrementAndGet();
                            // A background participant can legitimately consume the quote between the two commits.
                            if("FILLED".equals(result.status())) fills.incrementAndGet();
                            else {
                                assertTrue(background && "CANCELLED".equals(result.status()),result.toString());
                                cancelledMarkets.incrementAndGet();
                            }
                            latencies.add(System.nanoTime()-began); turn++;
                        }
                    } catch(Throwable error) { failures.add(error); }
                }));
            }
            ready.await(); long began=System.nanoTime(); deadline.set(began+TimeUnit.SECONDS.toNanos(seconds));
            if(background) {
                scheduled.scheduleWithFixedDelay(()->{try{tx.executeWithoutResult(s->market.maintainMarket());}catch(Throwable e){failures.add(e);}},0,3,TimeUnit.SECONDS);
                for(String code:codes) repeat(scheduled,market,tx,"liquidity:"+code,()->market.liquidityBotAction(code,"BOTH"),deadline,failures);
                for(String name:market.participantBotUsernames()) repeat(scheduled,market,tx,name,()->market.participantBotAction(name),deadline,failures);
                scheduled.scheduleWithFixedDelay(()->{try{market.snapshot();}catch(Throwable e){failures.add(e);}},0,250,TimeUnit.MILLISECONDS);
            }
            start.countDown();
            try { for(Future<?> job:jobs) job.get(seconds+120L,TimeUnit.SECONDS); }
            finally {
                pool.shutdownNow(); assertTrue(pool.awaitTermination(30,TimeUnit.SECONDS));
                scheduled.shutdown(); assertTrue(scheduled.awaitTermination(30,TimeUnit.SECONDS));
            }
            double elapsed=(System.nanoTime()-began)/1e9;
            long actual=db.queryForObject("SELECT COUNT(*) FROM trades",Long.class);
            long fees=db.queryForObject("SELECT COALESCE(SUM(buyer_fee+seller_fee),0) FROM trades",Long.class);
            long cashAfter=db.queryForObject("SELECT (SELECT SUM(cash) FROM users)+(SELECT COALESCE(SUM(reserved_cash),0) FROM orders WHERE status='OPEN')",Long.class);
            long sharesAfter=db.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class);
            List<Long> sorted=latencies.stream().sorted().toList();
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("seconds",elapsed); report.put("workers",workers); report.put("committedFills",actual);
            report.put("backgroundBotsAndSnapshots",background);report.put("workloadFills",fills.get());
            report.put("unfilledMarketCancellations",cancelledMarkets.get());
            report.put("fillsPerSecond",actual/elapsed); report.put("submittedOrders",submitted.get());
            report.put("pairLatencyP50Ms",percentile(sorted,.50)); report.put("pairLatencyP95Ms",percentile(sorted,.95)); report.put("pairLatencyP99Ms",percentile(sorted,.99));
            report.put("cashConservationError",cashAfter+fees-cashBefore); report.put("shareConservationError",sharesAfter-sharesBefore);
            report.put("durableFlush",db.queryForObject("SELECT @@innodb_flush_log_at_trx_commit",Integer.class));
            Map<String,Long> observedVolume=new LinkedHashMap<>();
            for(String code:codes.subList(0,Math.min(workers,codes.size()))) {
                long stock=db.queryForObject("SELECT id FROM stocks WHERE stock_code=?",Long.class,code);
                observedVolume.put(code,new PriceMetricService(db).read(stock,10000,System.currentTimeMillis(),0).volume());
            }
            report.put("recentMetricsVolume",observedVolume);
            report.put("failures",failures.stream().map(Throwable::toString).toList());
            Path output=Path.of("target/market-throughput",System.getProperty("market.throughput.label","latest")+".json");
            Files.createDirectories(output.getParent()); new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
            System.out.println("THROUGHPUT "+report);
            assertTrue(failures.isEmpty(),failures.toString());
            assertTrue(observedVolume.values().stream().allMatch(v->v>0),"real fills must reach the time-windowed indicators");
            if(background) assertTrue(actual>=fills.get()); else assertEquals(fills.get(),actual);
            assertEquals(cashBefore,cashAfter+fees); assertEquals(sharesBefore,sharesAfter);
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users WHERE cash<0",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM portfolios WHERE quantity<0 OR settled_quantity<0",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM trades WHERE buyer_id=seller_id",Integer.class));
            assertTrue(actual/elapsed>=Double.parseDouble(System.getProperty("market.throughput.minimum","0")),report.toString());
        }
    }
    private static double percentile(List<Long> sorted,double percentile) {
        return sorted.isEmpty()?0:sorted.get(Math.min(sorted.size()-1,(int)(sorted.size()*percentile)))/1e6;
    }
    private static void repeat(ScheduledExecutorService executor,MarketService market,TransactionTemplate tx,String name,
                               Runnable action,AtomicLong deadline,Queue<Throwable> failures) {
        if(executor.isShutdown() || System.nanoTime()>=deadline.get()) return;
        executor.schedule(()->{
            if(System.nanoTime()>=deadline.get()) return;
            try {tx.executeWithoutResult(s->action.run());} catch(Throwable e){failures.add(e);}
            repeat(executor,market,tx,name,action,deadline,failures);
        },market.nextBotDelay(name),TimeUnit.MILLISECONDS);
    }
}
