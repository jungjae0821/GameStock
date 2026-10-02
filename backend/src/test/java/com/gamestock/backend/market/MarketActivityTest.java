package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual independently generated bot orders, not injected matching order pairs. */
@EnabledIfSystemProperty(named="market.activity",matches="true")
class MarketActivityTest {
    @Test void perSymbolCommittedBotActivity() throws Exception {
        String url=System.getProperty("market.test.jdbc","");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13367/"),"disposable database only");
        String schema="activity_test_"+UUID.randomUUID().toString().replace("-","");
        HikariConfig adminConfig=new HikariConfig();adminConfig.setJdbcUrl(url);adminConfig.setUsername("root");adminConfig.setPassword("");adminConfig.setMaximumPoolSize(1);
        try(HikariDataSource admin=new HikariDataSource(adminConfig)) {
            JdbcTemplate root=new JdbcTemplate(admin);root.execute("CREATE DATABASE "+schema);
            try{run(url,schema);}finally{root.execute("DROP DATABASE "+schema);}
        }
    }
    private void run(String url,String schema) throws Exception {
        HikariConfig config=new HikariConfig();
        config.setJdbcUrl(url.replace("/?","/"+schema+"?")+"&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        config.setUsername("root");config.setPassword("");config.setMaximumPoolSize(10);
        config.addDataSourceProperty("useServerPrepStmts",true);
        config.addDataSourceProperty("cachePrepStmts",true);
        config.addDataSourceProperty("prepStmtCacheSize",128);
        config.addDataSourceProperty("prepStmtCacheSqlLimit",65536);
        try(HikariDataSource source=new HikariDataSource(config)) {
            JdbcTemplate db=new JdbcTemplate(source);
            String ddl=Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$","").replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
            for(String statement:ddl.split(";"))if(!statement.isBlank())db.execute(statement);
            MarketService market=new MarketService(e->{},db,new UserFeatureService(db),new TradingProtectionService(db));
            ReflectionTestUtils.setField(market,"configuredSeed",System.getProperty("market.activity.seed","12345"));
            ReflectionTestUtils.setField(market,"batchEnabled",true);
            ReflectionTestUtils.setField(market,"batchParticipants",Integer.getInteger("market.activity.participants",6020));
            ReflectionTestUtils.setField(market,"bookCacheEnabled",Boolean.parseBoolean(System.getProperty("market.activity.cache","true")));
            ReflectionTestUtils.setField(market,"compactLedger",Boolean.parseBoolean(System.getProperty("market.activity.compact","true")));
            ReflectionTestUtils.setField(market,"adaptiveActivity",true);
            ReflectionTestUtils.setField(market,"inlineMarketMaker",Boolean.parseBoolean(System.getProperty("market.activity.inline-lp","true")));
            market.initializeData();
            MarketMetrics pipelineMetrics=new MarketMetrics();
            MatchingEngine pipelineMatching=new MatchingEngine(pipelineMetrics);
            PersistenceWorker pipelineWriter=new PersistenceWorker(db,new com.fasterxml.jackson.databind.ObjectMapper(),pipelineMetrics);
            pipelineWriter.initialize();
            OrderService pipelineOrders=new OrderService(market,pipelineWriter,pipelineMetrics,e->{});
            boolean pipeline=Boolean.getBoolean("market.activity.pipeline");
            if(pipeline)market.configurePipeline(pipelineMatching,pipelineMetrics,null);
            market.maintainScheduledMarket();
            if(!Boolean.getBoolean("market.activity.idle"))market.marketViewerConnected("benchmark-viewer");
            TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(2);
            if(!market.inlineLiquidity()&&!Boolean.getBoolean("market.activity.idle"))
                for(String code:market.botStockCodes())tx.executeWithoutResult(s->market.liquidityBotAction(code,"BOTH"));
            db.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES ('activity_human','TEST','activity human',1000000)");
            long human=db.queryForObject("SELECT id FROM users WHERE username='activity_human'",Long.class);
            long cashBefore=db.queryForObject("SELECT (SELECT SUM(cash) FROM users)+(SELECT COALESCE(SUM(reserved_cash),0) FROM orders WHERE status='OPEN')",Long.class);
            long sharesBefore=db.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class);
            int warmup=Integer.getInteger("market.activity.warmup",10),seconds=Integer.getInteger("market.activity.seconds",30);
            Map<String,NavigableMap<Long,Integer>> fills=new TreeMap<>();
            Map<String,Set<Long>> protectedSeconds=new HashMap<>();
            for(String code:market.botStockCodes())fills.put(code,new TreeMap<>());
            List<Double> batchMillis=new ArrayList<>();Queue<Throwable> errors=new ConcurrentLinkedQueue<>();
            Map<String,Long> phases=new TreeMap<>();
            Queue<Double> userMillis=new ConcurrentLinkedQueue<>();
            java.util.concurrent.atomic.AtomicInteger protectedUserOrders=new java.util.concurrent.atomic.AtomicInteger();
            ScheduledThreadPoolExecutor background=new ScheduledThreadPoolExecutor(4);
            background.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            background.scheduleWithFixedDelay(()->{try{market.maintainScheduledMarket();}catch(Throwable e){errors.add(e);}},500,500,TimeUnit.MILLISECONDS);
            final int[] lpCursor={0};var codes=market.botStockCodes();
            if(!market.inlineLiquidity())background.scheduleWithFixedDelay(()->{try{String code=codes.get(lpCursor[0]++%codes.size());if(market.claimLegacyBotWork("liquidity:"+code))tx.executeWithoutResult(s->market.liquidityBotAction(code,"BOTH"));}catch(Throwable e){errors.add(e);}},100,100,TimeUnit.MILLISECONDS);
            if(!Boolean.getBoolean("market.activity.idle"))background.scheduleWithFixedDelay(()->{try{tx.execute(s->market.snapshot());}catch(Throwable e){errors.add(e);}},250,250,TimeUnit.MILLISECONDS);
            if(!Boolean.getBoolean("market.activity.idle"))background.scheduleWithFixedDelay(()->{try{
                long start=System.nanoTime();
                market.recordHumanActivity();
                int position=db.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM portfolios WHERE user_id=?",Integer.class,human);
                try{
                    var request=new MarketModels.OrderRequest("UMA",position>0?"SELL":"BUY",1,"MARKET",null);
                    if(pipeline)pipelineOrders.submit(human,request).join();else tx.executeWithoutResult(s->market.order(request,human));
                }
                catch(IllegalArgumentException halt) {if(market.marketStatus().restriction()!=null || db.queryForObject("SELECT vi_type IS NOT NULL FROM stock_protection_state p JOIN stocks s ON s.id=p.stock_id WHERE s.stock_code='UMA'",Boolean.class))protectedUserOrders.incrementAndGet();else throw halt;}
                userMillis.add((System.nanoTime()-start)/1e6);
            }catch(Throwable e){errors.add(e);}},3500,3500,TimeUnit.MILLISECONDS);
            long began=System.currentTimeMillis(),end=began+(seconds+warmup)*1000L,observationStart=(began+warmup*1000L+999)/1000;
            // Finish asynchronous pool/driver initialization before counting market workload SQL.
            var readyConnections=new ArrayList<java.sql.Connection>();
            try {for(int i=0;i<config.getMaximumPoolSize();i++)readyConnections.add(source.getConnection());}
            finally {for(var connection:readyConnections)connection.close();}
            var os=(com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long cpuStart=os.getProcessCpuTime(),peakHeap=0;
            Map<String,Long> databaseStart=databaseCounters(db);
            ExecutorService actors=Executors.newFixedThreadPool(market.batchShardCount());
            try {
                while(System.currentTimeMillis()<end) {
                    long start=System.nanoTime();
                    record Completed(List<BotActivityEngine.Activity> activity,long second,Map<String,Long> timing) { }
                    List<Future<Completed>> pending=new ArrayList<>();
                    for(int i=0;i<market.batchShardCount();i++) {
                        final int shard=i;
                        if(!market.botWorkDue("batch:"+shard))continue;
                        pending.add(actors.submit(()->new Completed(pipeline?pipelineOrders.submitBotBatch(shard).join():tx.execute(s->market.participantBatch(shard)),System.currentTimeMillis()/1000,market.batchTimings(shard))));
                    }
                    for(var future:pending) {
                        var committed=future.get(30,TimeUnit.SECONDS);
                        committed.timing().forEach((k,v)->phases.merge(k,v,Long::sum));
                        for(var activity:committed.activity()) {
                            fills.get(activity.code()).merge(committed.second(),activity.fills(),Integer::sum);
                            if(!activity.continuous())protectedSeconds.computeIfAbsent(activity.code(),k->new HashSet<>()).add(committed.second());
                        }
                    }
                    double millis=(System.nanoTime()-start)/1e6;batchMillis.add(millis);
                    peakHeap=Math.max(peakHeap,java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                    long cadence=market.participantCadenceMillis();
                    if(millis<cadence)Thread.sleep(Math.max(1,cadence-(long)millis));
                }
            } finally {actors.shutdown();background.shutdown();assertTrue(actors.awaitTermination(30,TimeUnit.SECONDS));assertTrue(background.awaitTermination(30,TimeUnit.SECONDS));pipelineOrders.close();pipelineWriter.close();pipelineMatching.close();}
            // Exclude the report's own inspection queries from the workload counters.
            Map<String,Long> workloadDatabaseEnd=databaseCounters(db);
            long workloadCpuEnd=os.getProcessCpuTime();
            long observationEnd=end/1000;int measured=(int)(observationEnd-observationStart);
            Map<String,Object> perSymbol=new TreeMap<>();
            for(var entry:fills.entrySet()) {
                List<Integer> series=new ArrayList<>(),allSeconds=new ArrayList<>();
                for(long s=observationStart;s<observationEnd;s++) {
                    int count=entry.getValue().getOrDefault(s,0);allSeconds.add(count);
                    if(!protectedSeconds.getOrDefault(entry.getKey(),Set.of()).contains(s))series.add(count);
                }
                long stock=db.queryForObject("SELECT id FROM stocks WHERE stock_code=?",Long.class,entry.getKey());
                var from=new java.sql.Timestamp(observationStart*1000);var until=new java.sql.Timestamp(observationEnd*1000);
                long all=db.queryForObject("SELECT COUNT(*) FROM trades WHERE stock_id=? AND created_at>=? AND created_at<?",Long.class,stock,from,until);
                long lp=db.queryForObject("SELECT COUNT(*) FROM trades t JOIN users u ON (t.buyer_id=u.id OR t.seller_id=u.id) WHERE t.stock_id=? AND u.username='liquidity_provider' AND t.created_at>=? AND t.created_at<?",Long.class,stock,from,until);
                all+=db.queryForObject("SELECT COALESCE(SUM(fill_count),0) FROM bot_ledger_batches WHERE stock_id=? AND created_at>=? AND created_at<?",Long.class,stock,from,until);
                lp+=db.queryForObject("SELECT COALESCE(SUM(lp_fills),0) FROM bot_ledger_batches WHERE stock_id=? AND created_at>=? AND created_at<?",Long.class,stock,from,until);
                perSymbol.put(entry.getKey(),Map.of("average",series.stream().mapToInt(i->i).average().orElse(0),"minimum",series.stream().mapToInt(i->i).min().orElse(0),"below30Seconds",series.stream().filter(i->i<30).count(),"zeroSeconds",series.stream().filter(i->i==0).count(),"series",allSeconds,"normalSeconds",series.size(),"excludedProtectionSeconds",measured-series.size(),"lpPercent",all==0?0:lp*100.0/all));
            }
            long fees=db.queryForObject("SELECT COALESCE(SUM(buyer_fee+seller_fee),0) FROM trades",Long.class);
            fees+=db.queryForObject("SELECT COALESCE(SUM(fees),0) FROM bot_ledger_totals",Long.class);
            long cashAfter=db.queryForObject("SELECT (SELECT SUM(cash) FROM users)+(SELECT COALESCE(SUM(reserved_cash),0) FROM orders WHERE status='OPEN')",Long.class);
            long sharesAfter=db.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class);
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("measuredSeconds",measured);report.put("warmupSeconds",warmup);report.put("perSymbol",perSymbol);
            report.put("measurement","Actual committed bot fills per fully continuous second; VI/circuit-breaker seconds are reported separately.");
            report.put("phaseNanos",phases);
            var users=userMillis.stream().sorted().toList();
            report.put("protectedUserOrders",protectedUserOrders.get());
            report.put("userOrderP95Ms",users.isEmpty()?0:users.get((int)(users.size()*.95)));
            var sorted=batchMillis.stream().sorted().toList();
            report.put("batchP50Ms",sorted.get(sorted.size()/2));report.put("batchP95Ms",sorted.get((int)(sorted.size()*.95)));report.put("batchMaxMs",sorted.get(sorted.size()-1));
            report.put("cashError",cashAfter+fees-cashBefore);report.put("shareError",sharesAfter-sharesBefore);
            long committedBots=fills.values().stream().flatMap(m->m.values().stream()).mapToLong(Integer::longValue).sum();
            long ledgerFills=db.queryForObject("SELECT COUNT(*) FROM trades",Long.class);
            ledgerFills+=db.queryForObject("SELECT COALESCE(SUM(fills),0) FROM bot_ledger_totals",Long.class);
            report.put("committedBotFillsIncludingWarmup",committedBots);report.put("ledgerFillsIncludingOtherOrders",ledgerFills);
            report.put("javaCpuSecondsIncludingWarmup",(workloadCpuEnd-cpuStart)/1e9);
            report.put("sampledPeakHeapMiB",peakHeap/1048576.0);
            Map<String,Long> delta=workloadDatabaseEnd;databaseStart.forEach((key,value)->delta.computeIfPresent(key,(k,total)->total-value));
            report.put("databaseCounterDeltasIncludingWarmup",delta);report.put("bookCache",market.bookCacheStats());
            report.put("compactJournalBytes",db.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(payload)),0) FROM bot_ledger_batches",Long.class));
            report.put("relationalTradeRows",db.queryForObject("SELECT COUNT(*) FROM trades",Long.class));
            report.put("relationalOrderRows",db.queryForObject("SELECT COUNT(*) FROM orders",Long.class));
            report.put("journalRows",db.queryForObject("SELECT COUNT(*) FROM bot_ledger_batches",Long.class));
            report.put("powerMode",market.activeSimulation()?"ACTIVE":"IDLE");
            report.put("restrictions",db.queryForList("SELECT * FROM trading_restriction_events"));
            report.put("failures",errors.stream().map(Throwable::toString).toList());
            report.put("durableFlush",db.queryForObject("SELECT @@innodb_flush_log_at_trx_commit",Integer.class));
            report.put("inventory",db.queryForList("SELECT SUBSTRING_INDEX(u.nickname,' ',1) strategy,COUNT(*) positions,SUM(p.quantity) quantity FROM portfolios p JOIN users u ON u.id=p.user_id GROUP BY strategy"));
            report.put("book",db.queryForList("SELECT stock_id,side,COUNT(*) orders,SUM(remaining_quantity) quantity,MIN(price) low,MAX(price) high FROM orders WHERE status='OPEN' GROUP BY stock_id,side"));
            report.put("prices",db.queryForList("SELECT stock_code,current_price FROM stocks"));
            Path path=Path.of("target/market-activity",System.getProperty("market.activity.label","latest")+".json");Files.createDirectories(path.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),report);
            System.out.println("ACTIVITY "+report);
            assertTrue(errors.isEmpty(),errors.toString());assertEquals(cashBefore,cashAfter+fees);assertEquals(sharesBefore,sharesAfter);
            assertTrue(ledgerFills>=committedBots,"only committed ledger fills may be counted");
            long[] audited={0};
            db.query("SELECT fill_count,payload FROM bot_ledger_batches ORDER BY id",rs->{
                var executions=BotLedgerJournal.decode(rs.getBytes(2)).fills();
                assertEquals(rs.getInt(1),executions.size(),"compressed detail must match committed counters");
                for(var fill:executions) {
                    assertNotEquals(fill.buyer(),fill.seller(),"bot-only executions also prevent self trades");
                    assertTrue(fill.quantity()>0 && fill.price()>0);
                    assertNotEquals(fill.buyOrder(),fill.sellOrder());
                    assertTrue(fill.makerOrder()==fill.buyOrder() || fill.makerOrder()==fill.sellOrder());
                    assertTrue(fill.takerOrder()==fill.buyOrder() || fill.takerOrder()==fill.sellOrder());
                    assertNotEquals(fill.makerOrder(),fill.takerOrder());
                }
                audited[0]+=executions.size();
            });
            assertEquals(db.queryForObject("SELECT COALESCE(SUM(fills),0) FROM bot_ledger_totals",Long.class),audited[0],"short benchmark retains all bot detail");
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM trades WHERE buyer_id=seller_id",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users WHERE cash<0",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM portfolios WHERE quantity<0 OR settled_quantity<0",Integer.class));
            if(Boolean.getBoolean("market.activity.idle"))for(String operation:List.of("Com_select","Com_insert","Com_update","Com_delete"))
                assertEquals(0L,delta.get(operation),"quiet market work must not reach MySQL: "+operation);
            if(Boolean.getBoolean("market.activity.acceptance"))for(Object result:perSymbol.values()) {
                Map<?,?> metric=(Map<?,?>)result;double average=((Number)metric.get("average")).doubleValue();
                assertTrue(((Number)metric.get("normalSeconds")).intValue()>=Math.min(30,measured/2),"not enough continuous trading observations: "+metric);
                if(Boolean.getBoolean("market.activity.idle"))assertEquals(0,average,"short idle probe must have no matching between half-hour refreshes");
                else {
                    assertTrue(average>=250&&average<=350,metric.toString());
                    assertTrue(((Number)metric.get("minimum")).intValue()>=30,metric.toString());
                }
            }
        }
    }
    private static Map<String,Long> databaseCounters(JdbcTemplate db) {
        Map<String,Long> result=new TreeMap<>();
        db.query("SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_select','Com_insert','Com_update','Com_delete','Innodb_data_written')",rs->{result.put(rs.getString(1),Long.parseLong(rs.getString(2)));});
        return result;
    }
}
