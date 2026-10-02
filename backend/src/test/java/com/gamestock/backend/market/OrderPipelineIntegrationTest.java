package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.gamestock.backend.market.MarketModels.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:13367/.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderPipelineIntegrationTest {
    HikariDataSource source;JdbcTemplate root,db;String database;
    MarketService market;MarketMetrics metrics;MatchingEngine matching;PersistenceWorker persistence;OrderService orders;
    long buyer,seller;List<Object> committed=new CopyOnWriteArrayList<>();
    @BeforeAll void start()throws Exception{
        String url=System.getProperty("market.test.jdbc");root=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));
        database="pipeline_"+UUID.randomUUID().toString().replace("-","");root.execute("CREATE DATABASE "+database);
        HikariConfig config=new HikariConfig();config.setJdbcUrl(url.replace("/?","/"+database+"?"));config.setUsername("root");config.setPassword("");config.setMaximumPoolSize(10);config.setConnectionInitSql("SET time_zone = '+00:00'");
        source=new HikariDataSource(config);db=new JdbcTemplate(source);
        String schema=Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$","").replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
        for(String statement:schema.split(";"))if(!statement.isBlank())db.execute(statement);
        metrics=new MarketMetrics();matching=new MatchingEngine(metrics);
        org.springframework.context.ApplicationEventPublisher events=event->{
            if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCommit(){committed.add(event);}});
            else committed.add(event);
        };
        market=new MarketService(events,db,new UserFeatureService(db),new TradingProtectionService(db));
        ReflectionTestUtils.setField(market,"configuredSeed","42");market.initializeData();market.configurePipeline(matching,metrics,null);
        persistence=new PersistenceWorker(db,new ObjectMapper(),metrics);persistence.initialize();
        orders=new OrderService(market,persistence,metrics,events);
        db.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES ('pipelinebuyer','TEST','pipelinebuyer',10000000),('pipelineseller','TEST','pipelineseller',10000000)");
        buyer=db.queryForObject("SELECT id FROM users WHERE username='pipelinebuyer'",Long.class);seller=db.queryForObject("SELECT id FROM users WHERE username='pipelineseller'",Long.class);
    }
    @BeforeEach void reset(){
        persistence.submit(UUID.randomUUID().toString(),Boolean.class,0,()->{
            market.maintainMarket();db.update("DELETE FROM settlements");db.update("DELETE FROM trades");db.update("DELETE FROM orders");db.update("DELETE FROM portfolios WHERE user_id IN (?,?)",buyer,seller);
            db.update("UPDATE users SET cash=10000000 WHERE id IN (?,?)",buyer,seller);
            db.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price) SELECT ?,id,1000,1000,10000 FROM stocks WHERE stock_code='UMA'",seller);
            return true;
        }).join();committed.clear();
    }
    @AfterAll void stop(){if(orders!=null)orders.close();if(persistence!=null)persistence.close();if(matching!=null)matching.close();if(source!=null)source.close();if(root!=null&&database!=null)root.execute("DROP DATABASE "+database);}
    @Test void humanCommandsMatchThroughTheSharedEngineAndOnlyCommittedFillsArePublished()throws Exception{
        orders.submit(seller,new OrderRequest("UMA","SELL",10,"LIMIT",10000L)).get(5,TimeUnit.SECONDS);
        var result=orders.submit(buyer,new OrderRequest("UMA","BUY",7,"MARKET",null)).get(5,TimeUnit.SECONDS);
        assertEquals("FILLED",result.status());assertEquals(7,db.queryForObject("SELECT quantity FROM portfolios WHERE user_id=?",Integer.class,buyer));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM trades",Integer.class));
        assertTrue(committed.stream().filter(UserMarketEvent.class::isInstance).map(UserMarketEvent.class::cast).anyMatch(e->e.userId()==buyer));
        assertTrue(metrics.snapshot().getOrDefault("matching.count",0L)>=2);
    }
    @Test void concurrentHumanCommandsPreserveCashReservationsAndIndividualOrders()throws Exception{
        List<CompletableFuture<OrderResult>> requests=new ArrayList<>();
        for(int i=1;i<=20;i++)requests.add(orders.submit(buyer,new OrderRequest("UMA","BUY",i,"LIMIT",9900L)));
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);
        assertEquals(20,db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?",Integer.class,buyer));
        long cash=db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer),reserved=db.queryForObject("SELECT SUM(reserved_cash) FROM orders WHERE user_id=?",Long.class,buyer);
        assertEquals(10000000,cash+reserved);assertEquals(210,db.queryForObject("SELECT SUM(quantity) FROM orders WHERE user_id=?",Integer.class,buyer));
    }
    @Test void failedBatchRollsBackThenRetriesAndReceiptPreventsDoubleExecution(){
        String id=UUID.randomUUID().toString();AtomicInteger attempts=new AtomicInteger();
        var task=(java.util.function.Supplier<Long>)()->{
            db.update("UPDATE users SET cash=cash-100 WHERE id=?",buyer);
            if(attempts.incrementAndGet()==1)throw new org.springframework.dao.CannotAcquireLockException("injected rollback");
            return 12L;
        };
        assertEquals(12L,persistence.submit(id,Long.class,1,task).join());
        assertEquals(12L,persistence.submit(id,Long.class,1,task).join());
        assertEquals(2,attempts.get());assertEquals(9999900,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM market_batch_receipts WHERE id=?",Integer.class,id));
    }
    @Test void retryIsBoundedAndNoRolledBackUserEventEscapes(){
        AtomicInteger tries=new AtomicInteger();
        var result=persistence.submit(UUID.randomUUID().toString(),Boolean.class,1,()->{
            market.order(new OrderRequest("UMA","BUY",1,"LIMIT",9900L),buyer);tries.incrementAndGet();
            throw new org.springframework.dao.CannotAcquireLockException("persistent failure");
        });
        assertThrows(CompletionException.class,result::join);assertEquals(4,tries.get());assertTrue(committed.isEmpty());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?",Integer.class,buyer));
        assertEquals(10000000,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer));
    }
    @Test void anotherLaneProgressesDuringOneSlowDatabaseBatch()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var slow=persistence.submit(UUID.randomUUID().toString(),Boolean.class,1,()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}return true;});
        try{assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(3,persistence.submit(UUID.randomUUID().toString(),Integer.class,1,()->3).get(2,TimeUnit.SECONDS));}
        finally{release.countDown();slow.get(5,TimeUnit.SECONDS);}
    }
    @Test void onlyOneReplicaCanOwnMatching()throws Exception{
        var first=new MarketOwnership(db);first.start();
        var second=new MarketOwnership(db);
        try{first.requireOwner();assertThrows(IllegalStateException.class,second::start);}
        finally{first.close();second.close();}
    }
    @Test void shutdownFlushesAdmittedHumanOrders()throws Exception{
        var local=new OrderService(market,persistence,metrics,event->{});
        var request=local.submit(buyer,new OrderRequest("UMA","BUY",3,"LIMIT",9900L));local.close();
        assertEquals("OPEN",request.get(3,TimeUnit.SECONDS).status());assertEquals(3,db.queryForObject("SELECT SUM(remaining_quantity) FROM orders WHERE user_id=?",Integer.class,buyer));
    }
    @Test void cancellationSharesAdmissionOrderAndRefundsOnce()throws Exception{
        orders.submit(buyer,new OrderRequest("UMA","BUY",3,"LIMIT",9900L)).get(5,TimeUnit.SECONDS);
        long id=db.queryForObject("SELECT id FROM orders WHERE user_id=?",Long.class,buyer);
        orders.cancel(buyer,id).get(5,TimeUnit.SECONDS);
        assertThrows(ExecutionException.class,()->orders.cancel(buyer,id).get(5,TimeUnit.SECONDS));
        assertEquals(10000000,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer));
        assertTrue(committed.stream().filter(UserMarketEvent.class::isInstance).map(UserMarketEvent.class::cast).anyMatch(e->e.type().equals("ORDER_CANCELLED")));
    }
    @org.springframework.context.annotation.Configuration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions{}
    @Test void productionBeansWireAndDrainBeforeConnectionsAndMatchingAreClosed()throws Exception{
        CompletableFuture<OrderResult> pending;
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of("market.batch.enabled","false","market.seed","42")));
            context.registerBean(JdbcTemplate.class,()->db);
            context.registerBean(ObjectMapper.class,()->new ObjectMapper());
            context.registerBean(org.springframework.jdbc.datasource.DataSourceTransactionManager.class,()->new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            context.registerBean(org.springframework.transaction.event.TransactionalEventListenerFactory.class);
            context.registerBean(com.gamestock.backend.realtime.EventFanout.class,()->new com.gamestock.backend.realtime.LocalEventFanout());
            context.register(Transactions.class,MarketMetrics.class,MatchingEngine.class,MarketOwnership.class,UserFeatureService.class,TradingProtectionService.class,MarketService.class,
                com.gamestock.backend.realtime.ConnectionManager.class,com.gamestock.backend.realtime.MarketBroadcaster.class,PersistenceWorker.class,OrderService.class);
            context.refresh();
            pending=context.getBean(OrderService.class).submit(buyer,new OrderRequest("UMA","BUY",2,"LIMIT",9900L));
        }
        assertEquals("OPEN",pending.get(5,TimeUnit.SECONDS).status());
    }
}
