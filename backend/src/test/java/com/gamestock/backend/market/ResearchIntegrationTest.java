package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfSystemProperty(named="research.test.jdbc",matches="jdbc:mysql://.*")
class ResearchIntegrationTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    HikariDataSource admin,source;String schema;
    AnnotationConfigApplicationContext context;
    JdbcTemplate db;DiscussionService discussions;QuantProjectService projects;QuantScheduler scheduler;
    MarketService market;OrderService orders;
    @BeforeEach void setup() {
        HikariConfig root=new HikariConfig();root.setJdbcUrl(System.getProperty("research.test.jdbc"));
        root.setUsername(System.getProperty("research.test.user","root"));root.setPassword(System.getProperty("research.test.password",""));
        admin=new HikariDataSource(root);schema="research_test_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(admin).execute("CREATE DATABASE "+schema);
        HikariConfig config=new HikariConfig();config.setJdbcUrl(root.getJdbcUrl().replaceFirst("/[^/?]*(\\?|$)","/"+schema+"$1"));
        config.setUsername(root.getUsername());config.setPassword(root.getPassword());source=new HikariDataSource(config);db=new JdbcTemplate(source);
        db.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,nickname VARCHAR(30),password_hash VARCHAR(100),role VARCHAR(20),trade_banned_until TIMESTAMP NULL)");
        db.execute("CREATE TABLE stocks(id BIGINT PRIMARY KEY,stock_code VARCHAR(32) UNIQUE,current_price BIGINT)");
        db.update("INSERT INTO users VALUES(1,'alice','GOOGLE','USER',NULL),(2,'bob','GOOGLE','USER',NULL),(3,'admin','GOOGLE','ADMIN',NULL)");
        db.update("INSERT INTO stocks VALUES(1,'GI',10000),(2,'NI',20000)");
        ResearchSchema.ensureTables(db);ResearchSchema.ensureTables(db);
        market=mock(MarketService.class);orders=mock(OrderService.class);when(market.ownerAvailable()).thenReturn(true);
        long start=Instant.now().getEpochSecond()/60*60-60;
        var bar=new MarketModels.ChartCandle(Instant.ofEpochSecond(start).toString(),10000,10000,10000,10000,1,Instant.ofEpochSecond(start).toString());
        when(market.chartHistory("GI","1h")).thenReturn(List.of(bar));
        when(orders.submit(anyLong(),any())).thenReturn(CompletableFuture.completedFuture(new MarketModels.OrderResult("ok","GI","BUY",2,10000,"OPEN",null,0,null,null,777L)));
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class,()->db);context.registerBean(ObjectMapper.class,()->new ObjectMapper());
        context.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(MarketService.class,()->market);context.registerBean(OrderService.class,()->orders);
        context.register(DiscussionService.class,ResearchChartService.class,QuantProjectService.class,QuantScheduler.class);context.refresh();
        discussions=context.getBean(DiscussionService.class);projects=context.getBean(QuantProjectService.class);scheduler=context.getBean(QuantScheduler.class);
    }
    @AfterEach void cleanup() {
        if(context!=null)context.close();if(source!=null)source.close();
        if(admin!=null) {if(schema!=null)new JdbcTemplate(admin).execute("DROP DATABASE "+schema);admin.close();}
    }
    QuantProjectService.Save request(Integer revision) {
        return new QuantProjectService.Save("demo","GI","1h",new QuantProjectService.Source("SNSCRIPT","buy when true quantity 2",null),5,100000,revision);
    }
    QuantProjectService.Project active() {
        var p=projects.save(1,null,request(null));return projects.status(1,p.id(),true,p.revision());
    }
    @Test void upstreamTalkAndChatRemainIndependent() {
        StockTalkService.ensureTables(db);
        var talk=new StockTalkService(db,new DataSourceTransactionManager(source));
        var post=talk.write(1,"alice","GI",new StockTalkService.Write("body",null));
        var reply=talk.write(2,"bob","GI",new StockTalkService.Write("reply",post.id()));
        assertTrue(talk.like(2,post.id()).liked());
        var page=talk.list("GI",0,1);
        assertEquals(1,page.posts().size());assertEquals(1,page.posts().get(0).likes());
        assertEquals(reply.id(),page.posts().get(0).replies().get(0).id());
        var chat=discussions.create(1,"GI",new DiscussionService.Write(null,"hello"));
        assertEquals(List.of(chat),discussions.list("GI",null,0L,30).items());
        assertTrue(discussions.list("NI",null,0L,30).items().isEmpty());
        assertTrue(discussions.list("GI",null,chat.id(),30).items().isEmpty());
        assertThrows(ResponseStatusException.class,()->discussions.delete(2,false,"GI",chat.id()));
        talk.delete(1,post.id());assertTrue(talk.list("GI",0,1).posts().isEmpty());
        assertFalse(discussions.get("GI",chat.id()).deleted());
        discussions.delete(3,true,"GI",chat.id());assertTrue(discussions.get("GI",chat.id()).deleted());
    }
    @Test void chatRateLimitRollsBackRejectedWrites() {
        for(int i=0;i<30;i++)discussions.create(1,"GI",new DiscussionService.Write(null,"hello"));
        assertEquals(429,assertThrows(ResponseStatusException.class,()->discussions.create(1,"GI",new DiscussionService.Write(null,"extra"))).getStatusCode().value());
        assertEquals(30,db.queryForObject("SELECT COUNT(*) FROM stock_discussions",Integer.class));
        assertEquals(30,db.queryForObject("SELECT actions FROM research_write_limits WHERE user_id=1 AND channel='CHAT'",Integer.class));
    }
    @Test void privateProjectsRequireOwnershipAndRevisionAndPauseBeforeEdits() {
        var p=projects.save(1,null,request(null));assertEquals("PAUSED",p.status());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->projects.get(2,p.id())).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->projects.save(1,p.id(),request(99)));
        var edited=projects.save(1,p.id(),request(1));assertEquals(2,edited.revision());
        projects.status(1,p.id(),true,2);assertThrows(ResponseStatusException.class,()->projects.save(1,p.id(),request(2)));
        assertThrows(ResponseStatusException.class,()->projects.delete(1,p.id()));
        projects.status(1,p.id(),false,2);projects.delete(1,p.id());assertTrue(projects.list(1).isEmpty());
    }
    @Test void eachClosedCandleProducesAtMostOneOrderAndDurableRun() {
        var p=active();scheduler.tick();scheduler.tick();
        verify(orders,times(1)).submit(eq(1L),argThat(r->r.orderType().equals("LIMIT") && r.quantity()==2 && r.price()==10000));
        var actual=projects.get(1,p.id());assertEquals(1,actual.ordersUsed());assertEquals(20000,actual.notionalUsed());
        var run=projects.runs(1,p.id(),null,30).get(0);assertEquals("ACCEPTED",run.status());assertEquals(777L,run.orderId());
        projects.status(1,p.id(),false,1);projects.status(1,p.id(),true,1);scheduler.tick();verify(orders,times(1)).submit(anyLong(),any());
    }
    @Test void pauseDuringSubmissionStopsFutureSignalsAndDoesNotCancelSubmittedOrder() {
        var pending=new CompletableFuture<MarketModels.OrderResult>();when(orders.submit(anyLong(),any())).thenReturn(pending);
        var p=active();scheduler.tick();assertEquals("RUNNING",projects.get(1,p.id()).status());
        assertEquals("PAUSING",projects.status(1,p.id(),false,1).status());
        pending.complete(new MarketModels.OrderResult("ok","GI","BUY",2,10000,"OPEN",null));
        assertEquals("PAUSED",projects.get(1,p.id()).status());
    }
    @Test void unknownSubmissionIsPausedAndCannotRepeatSameBar() {
        when(orders.submit(anyLong(),any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("lost receipt")));
        var p=active();scheduler.tick();assertEquals("PAUSED",projects.get(1,p.id()).status());
        assertEquals("UNKNOWN",projects.runs(1,p.id(),null,10).get(0).status());
        projects.status(1,p.id(),true,1);scheduler.tick();verify(orders,times(1)).submit(anyLong(),any());
    }
    @Test void limitsAndAccountSuspensionPreventAdmission() {
        var p=active();db.update("UPDATE quant_projects SET max_notional=1 WHERE id=?",p.id());scheduler.tick();
        assertEquals("PAUSED",projects.get(1,p.id()).status());verifyNoInteractions(orders);
        db.update("UPDATE users SET trade_banned_until=CURRENT_TIMESTAMP+INTERVAL 1 DAY WHERE id=1");
        assertThrows(ResponseStatusException.class,()->projects.status(1,p.id(),true,1));
        db.update("UPDATE quant_projects SET status='ACTIVE',max_notional=100000 WHERE id=?",p.id());scheduler.tick();
        assertEquals("PAUSED",projects.get(1,p.id()).status());verifyNoInteractions(orders);
        var adminProject=projects.save(3,null,request(null));assertThrows(ResponseStatusException.class,()->projects.status(3,adminProject.id(),true,1));
    }
    @Test void concurrentSchedulersClaimOnlyOnce() throws Exception {
        active();ExecutorService workers=Executors.newFixedThreadPool(2);
        try {var a=workers.submit(scheduler::tick);var b=workers.submit(scheduler::tick);a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}
        finally {workers.shutdownNow();}
        verify(orders,times(1)).submit(anyLong(),any());
    }
    @Test void abandonedSubmissionIsMarkedUnknownAndPaused() {
        when(orders.submit(anyLong(),any())).thenReturn(new CompletableFuture<>());
        var p=active();scheduler.tick();
        db.update("UPDATE quant_projects SET updated_at=CURRENT_TIMESTAMP-INTERVAL 10 MINUTE WHERE id=?",p.id());
        scheduler.tick();assertEquals("PAUSED",projects.get(1,p.id()).status());
        assertEquals("UNKNOWN",projects.runs(1,p.id(),null,10).get(0).status());verify(orders,times(1)).submit(anyLong(),any());
    }
}
