package com.gamestock.backend.market;

import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.MarketModels.*;

/** Exercises the actual Spring transaction annotations, with two backend instances and pooled connections. */
@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:13367/.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketConcurrencyTest {
    private HikariDataSource source,admin;
    private JdbcTemplate db;
    private String schema;
    private MarketService first,second;
    private TransactionTemplate tx;

    @BeforeAll void setup() throws Exception {
        String url=System.getProperty("market.test.jdbc");
        HikariConfig root=new HikariConfig(); root.setJdbcUrl(url); root.setUsername("root");root.setPassword("");root.setMaximumPoolSize(1);
        admin=new HikariDataSource(root); schema="concurrency_test_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(admin).execute("CREATE DATABASE "+schema);
        HikariConfig c=new HikariConfig(); c.setJdbcUrl(url.replace("/?","/"+schema+"?")+"&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");c.setUsername("root");c.setPassword("");
        c.setMaximumPoolSize(12);c.setConnectionInitSql("SET time_zone='+00:00'");
        source=new HikariDataSource(c);db=new JdbcTemplate(source);
        String ddl=Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$","")
                .replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
        for(String statement:ddl.split(";")) if(!statement.isBlank()) db.execute(statement);
        // Reservation and contention fixtures explicitly trade around 10,000 won.
        StockCatalog.ensureListings(db);
        db.update("UPDATE stocks SET current_price=10000,previous_price=10000");
        var manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        for(int i=0;i<2;i++) {
            var target=new MarketService(e->{},db,new UserFeatureService(db),new TradingProtectionService(db));
            ReflectionTestUtils.setField(target,"configuredSeed","12345");target.initializeData();
            var factory=new ProxyFactory(target); factory.setProxyTargetClass(true);
            factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
            if(i==0) first=(MarketService)factory.getProxy(); else second=(MarketService)factory.getProxy();
        }
    }
    @AfterEach void cleanupOrders() { tx.executeWithoutResult(s->{
        db.queryForObject("SELECT id FROM market_locks WHERE id=1 FOR UPDATE",Integer.class);
        for(var row:db.queryForList("SELECT id,user_id FROM orders WHERE status='OPEN'"))
            first.cancelOrder(((Number)row.get("id")).longValue(),((Number)row.get("user_id")).longValue());
    }); }
    @AfterAll void cleanup() {
        if(source!=null) source.close();
        if(admin!=null){if(schema!=null)new JdbcTemplate(admin).execute("DROP DATABASE "+schema);admin.close();}
    }
    private long user(long cash) {
        String name="concurrent_"+UUID.randomUUID();
        // BOT denotes a liquidity provider with a protected emergency cash reserve.
        // These actors exercise ordinary automated participants instead.
        db.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES (?,'TRADER',?,?)",name,name,cash);
        return db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
    }
    private void shares(long user,String code,int quantity) {
        db.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price) SELECT ?,id,?,?,10000 FROM stocks WHERE stock_code=?",user,quantity,quantity,code);
    }
    private void parallel(int count,java.util.function.IntConsumer action) throws Exception {
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);List<Future<?>> jobs=new ArrayList<>();
        try {
            for(int i=0;i<count;i++){int n=i;jobs.add(pool.submit(()->{try{start.await();action.accept(n);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}));}
            start.countDown();for(var job:jobs) job.get(30,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void accountResetInvalidatesWarmBooksAndCannotResurrectCancelledOrders() {
        long buyer=user(1000000),seller=user(0);shares(seller,"UMA",20);
        first.order(new OrderRequest("UMA","SELL",10,"LIMIT",10000L),seller);
        long stock=db.queryForObject("SELECT id FROM stocks WHERE stock_code='UMA'",Long.class);
        long order=db.queryForObject("SELECT id FROM orders WHERE user_id=? AND status='OPEN'",Long.class,seller);
        var cache=new MarketBookCache();
        var original=tx.execute(s->{
            var repository=new BatchMarketRepository(db);long now=System.currentTimeMillis();
            var book=cache.borrow(repository,now,Set.of(stock),Set.of(buyer,seller));
            repository.persist(book,now);cache.saved(now);return book;
        });
        db.update("UPDATE users SET nickname_changed_at=CURRENT_TIMESTAMP WHERE id=?",seller);
        tx.execute(s->new com.gamestock.backend.auth.AccountResetService(db,new com.gamestock.backend.auth.AuthService(db)).reset(seller));
        tx.executeWithoutResult(s->{
            var repository=new BatchMarketRepository(db);long now=System.currentTimeMillis();
            var book=cache.borrow(repository,now,Set.of(stock),Set.of(buyer,seller));
            assertNotSame(original,book);
            book.submit(buyer,stock,"BUY","MARKET",4,0,now,0);
            assertTrue(book.fills.isEmpty());
            repository.persist(book,now);cache.saved(now);
        });
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
        assertEquals(1000000,first.portfolio(seller).cash());
        assertTrue(first.portfolio(seller).positions().isEmpty());
        assertNull(db.queryForObject("SELECT nickname_changed_at FROM users WHERE id=?",java.sql.Timestamp.class,seller));
    }

    @Test void humanMarketDoubleClickIsRejectedAndResetHidesPriorSettlements() {
        long buyer=user(1000000),seller=user(0);shares(seller,"UMA",20);
        db.update("UPDATE users SET password_hash='TEST' WHERE id=?",buyer);
        first.order(new OrderRequest("UMA","SELL",10,"LIMIT",10000L),seller);
        assertEquals("FILLED",first.order(new OrderRequest("UMA","BUY",1,"MARKET",null),buyer).status());
        assertThrows(IllegalArgumentException.class,()->first.order(new OrderRequest("UMA","BUY",1,"MARKET",null),buyer));
        assertEquals(1,first.settlements(buyer).size());
        tx.execute(s->new com.gamestock.backend.auth.AccountResetService(db,new com.gamestock.backend.auth.AuthService(db)).reset(buyer));
        assertTrue(first.settlements(buyer).isEmpty());
    }

    @Test void settlementResetBoundaryDoesNotRoundAMillisecondTradeIntoTheNextSecond() {
        long buyer=user(1000000),seller=user(0);shares(seller,"UMA",20);
        first.order(new OrderRequest("UMA","SELL",10,"LIMIT",10000L),seller);
        first.order(new OrderRequest("UMA","BUY",1,"MARKET",null),buyer);
        java.time.Instant second=java.time.Instant.parse("2030-01-01T00:00:00Z");
        var filled=java.sql.Timestamp.from(second.plusMillis(800));
        db.update("UPDATE settlements SET created_at=? WHERE buyer_id=?",filled,buyer);
        assertEquals(filled,db.queryForObject("SELECT created_at FROM settlements WHERE buyer_id=?",java.sql.Timestamp.class,buyer));
        db.update("UPDATE users SET account_reset_at=? WHERE id=?",java.sql.Timestamp.from(second.plusMillis(850)),buyer);
        assertTrue(first.settlements(buyer).isEmpty());
        db.update("UPDATE settlements SET created_at=? WHERE buyer_id=?",java.sql.Timestamp.from(second.plusMillis(900)),buyer);
        assertEquals(1,first.settlements(buyer).size(),"Trades after the reset boundary must remain visible");
    }

    @Test void cancelAllIncludesMoreThanOneHundredOrdersAndReleasesAllReservations() {
        long seller=user(0);shares(seller,"UMA",200);
        long stock=db.queryForObject("SELECT id FROM stocks WHERE stock_code='UMA'",Long.class);
        for(int i=0;i<105;i++) db.update("INSERT INTO orders(user_id,stock_id,side,order_type,price,quantity,remaining_quantity,reserved_quantity,status) VALUES (?,?,'SELL','LIMIT',10000,1,1,1,'OPEN')",seller,stock);
        assertEquals(95,first.portfolio(seller).positions().get(0).availableQuantity());
        assertEquals(5,first.activeOrders(seller,"UMA").size());
        assertTrue(first.activeOrders(seller,"BA").isEmpty());
        assertEquals(105,first.cancelAllOrders(seller));
        assertTrue(first.activeOrders(seller).isEmpty());
        assertEquals(200,first.portfolio(seller).positions().get(0).availableQuantity());
    }

    @Test void mobileLoginCodeIsSharedAndConsumedExactlyOnceAcrossInstances() throws Exception {
        var issuer=new com.gamestock.backend.auth.AuthService(db);
        var redeemer=new com.gamestock.backend.auth.AuthService(db);
        issuer.initializeMobileCodes();
        var firebase=org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseAuth.class);
        var token=org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        org.mockito.Mockito.when(token.getUid()).thenReturn("test-user");
        org.mockito.Mockito.when(firebase.verifyIdToken("valid",true)).thenReturn(token);
        org.mockito.Mockito.when(firebase.createCustomToken("test-user")).thenReturn("test-custom-token");
        try(var mocked=org.mockito.Mockito.mockStatic(com.google.firebase.auth.FirebaseAuth.class)) {
            mocked.when(com.google.firebase.auth.FirebaseAuth::getInstance).thenReturn(firebase);
            String verifier="a".repeat(43),challenge=com.gamestock.backend.auth.AuthService.pkceChallenge(verifier);
            var issued=issuer.issueMobileCode("Bearer valid",challenge);
            AtomicInteger succeeded=new AtomicInteger(),rejected=new AtomicInteger();
            parallel(2,i->{
                try {
                    var result=tx.execute(s->redeemer.exchangeMobileCode(new com.gamestock.backend.auth.AuthService.MobileCodeExchange(issued.code(),null,verifier)));
                    assertEquals("test-custom-token",result.customToken());succeeded.incrementAndGet();
                } catch(org.springframework.web.server.ResponseStatusException error) {
                    assertEquals(401,error.getStatusCode().value());rejected.incrementAndGet();
                }
            });
            assertEquals(1,succeeded.get());assertEquals(1,rejected.get());
            var expired=issuer.issueMobileCode("Bearer valid",challenge);
            db.update("UPDATE mobile_login_codes SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)");
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.execute(s->redeemer.exchangeMobileCode(new com.gamestock.backend.auth.AuthService.MobileCodeExchange(expired.code(),null,verifier))));
            // 가로챈 코드만으로는(검증값이 없거나 다르면) 토큰을 받을 수 없다. 그래도 원래 앱의 로그인은 막히지 않는다.
            var intercepted=issuer.issueMobileCode("Bearer valid",challenge);
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.execute(s->redeemer.exchangeMobileCode(new com.gamestock.backend.auth.AuthService.MobileCodeExchange(intercepted.code(),null,null))));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.execute(s->redeemer.exchangeMobileCode(new com.gamestock.backend.auth.AuthService.MobileCodeExchange(intercepted.code(),null,"b".repeat(43)))));
            assertEquals("test-custom-token",tx.execute(s->redeemer.exchangeMobileCode(new com.gamestock.backend.auth.AuthService.MobileCodeExchange(intercepted.code(),null,verifier))).customToken());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.execute(s->redeemer.exchangeMobileCode(new com.gamestock.backend.auth.AuthService.MobileCodeExchange(intercepted.code(),null,verifier))));
            // 검증값 없이 만든 코드는 발급하지 않는다.
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->issuer.issueMobileCode("Bearer valid",null));
        }
    }
    @Test void sharedCashCannotBeOverspentAcrossSymbolsOrInstances() throws Exception {
        long buyer=user(100100),seller=user(0);shares(seller,"UMA",20);shares(seller,"BA",20);
        for(String code:List.of("UMA","BA")) first.order(new OrderRequest(code,"SELL",20,"LIMIT",10000L),seller);
        AtomicInteger filled=new AtomicInteger(),rejected=new AtomicInteger();
        parallel(40,i->{try {
            var result=(i%2==0?first:second).order(new OrderRequest(i%2==0?"UMA":"BA","BUY",1,"MARKET",null),buyer);
            assertEquals("FILLED",result.status());filled.incrementAndGet();
        } catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("현금"));rejected.incrementAndGet();}});
        assertEquals(10,filled.get());assertEquals(30,rejected.get());
        assertEquals(100,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer));
        assertEquals(10,db.queryForObject("SELECT SUM(quantity) FROM portfolios WHERE user_id=?",Integer.class,buyer));
    }
    @Test void cachedBookReusesCommittedStateAndReloadsAfterExternalOrderAndRollback() {
        long buyer=user(1000000),seller=user(0);shares(seller,"UMA",20);
        long stock=db.queryForObject("SELECT id FROM stocks WHERE stock_code='UMA'",Long.class);
        var cache=new MarketBookCache();var users=Set.of(buyer,seller);var symbols=Set.of(stock);
        long counterTime=System.currentTimeMillis();
        AtomicInteger previousFills=new AtomicInteger();
        var original=tx.execute(s->{
            var repository=new BatchMarketRepository(db);long now=counterTime;
            var book=cache.borrow(repository,now,symbols,users);
            previousFills.set(cache.recent(now).getOrDefault(stock,0));
            book.submit(seller,stock,"SELL","LIMIT",10,10000,now,60000);
            book.submit(buyer,stock,"BUY","MARKET",2,0,now,0);
            repository.persist(book,now);cache.saved(now);return book;
        });
        tx.executeWithoutResult(s->{
            var repository=new BatchMarketRepository(db);long now=counterTime;
            var book=cache.borrow(repository,now,symbols,users);assertSame(original,book);
            assertEquals(2,book.holding(buyer,stock).quantity());assertEquals(previousFills.get()+1,cache.recent(now).get(stock));
            book.submit(buyer,stock,"BUY","MARKET",1,0,now,0);repository.persist(book,now);cache.saved(now);
        });
        second.order(new OrderRequest("UMA","BUY",1,"MARKET",null),buyer);
        tx.executeWithoutResult(s->{
            var repository=new BatchMarketRepository(db);long now=System.currentTimeMillis();
            var book=cache.borrow(repository,now,symbols,users);assertNotSame(original,book);
            assertEquals(4,book.holding(buyer,stock).quantity());
            book.submit(buyer,stock,"BUY","MARKET",1,0,now,0);repository.persist(book,now);cache.saved(now);s.setRollbackOnly();
        });
        tx.executeWithoutResult(s->{
            var repository=new BatchMarketRepository(db);long now=System.currentTimeMillis();
            var book=cache.borrow(repository,now,symbols,users);
            assertEquals(4,book.holding(buyer,stock).quantity());assertEquals(6,book.working(seller,stock).get(0).remaining);
            repository.persist(book,now);cache.saved(now);
        });
        assertEquals(1,cache.hits());assertEquals(3,cache.misses());
    }
    @Test void concurrentBatchesValidateSharedCashBeforeWritingEitherLedger() throws Exception {
        long buyer=user(10010),seller=user(0);shares(seller,"UMA",1);shares(seller,"BA",1);
        for(String code:List.of("UMA","BA"))first.order(new OrderRequest(code,"SELL",1,"LIMIT",10000L),seller);
        CountDownLatch planned=new CountDownLatch(2);AtomicInteger committed=new AtomicInteger(),retried=new AtomicInteger();
        parallel(2,i->{
            try {tx.executeWithoutResult(status->{
                String code=i==0?"UMA":"BA";long stock=db.queryForObject("SELECT id FROM stocks WHERE stock_code=?",Long.class,code);
                long now=System.currentTimeMillis();var repository=new BatchMarketRepository(db);
                var book=repository.load(now,Set.of(stock),Set.of(buyer,seller));
                assertNotNull(book.submit(buyer,stock,"BUY","LIMIT",1,10000,now,1000));assertEquals(1,book.fills.size());
                planned.countDown();try{assertTrue(planned.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                repository.persist(book,now);
            });committed.incrementAndGet();}catch(org.springframework.dao.CannotAcquireLockException expected){retried.incrementAndGet();}
        });
        assertEquals(1,committed.get());assertEquals(1,retried.get());
        assertEquals(10,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM trades WHERE buyer_id=?",Integer.class,buyer));
        assertEquals(1,db.queryForObject("SELECT SUM(quantity) FROM portfolios WHERE user_id=?",Integer.class,buyer));
    }
    @Test void simultaneousSymbolSalesMergeCashDeltasWithoutLostUpdates() throws Exception {
        long seller=user(0),a=user(10010),b=user(10010);shares(seller,"UMA",1);shares(seller,"BA",1);
        for(String code:List.of("UMA","BA"))first.order(new OrderRequest(code,"SELL",1,"LIMIT",10000L),seller);
        CountDownLatch planned=new CountDownLatch(2);
        parallel(2,i->tx.executeWithoutResult(status->{
            long buyer=i==0?a:b;String code=i==0?"UMA":"BA";long stock=db.queryForObject("SELECT id FROM stocks WHERE stock_code=?",Long.class,code);
            long now=System.currentTimeMillis();var repository=new BatchMarketRepository(db);var book=repository.load(now,Set.of(stock),Set.of(seller,buyer));
            book.submit(buyer,stock,"BUY","LIMIT",1,10000,now,1000);
            planned.countDown();try{assertTrue(planned.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
            repository.persist(book,now);
        }));
        assertEquals(20000,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,seller));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM trades WHERE seller_id=?",Integer.class,seller));
    }
    @Test void sharedInventoryCannotBeSoldTwice() throws Exception {
        long buyer=user(1000000),seller=user(0);shares(seller,"UMA",10);
        first.order(new OrderRequest("UMA","BUY",30,"LIMIT",10000L),buyer);
        AtomicInteger filled=new AtomicInteger();
        parallel(30,i->{try {
            var result=(i%2==0?first:second).order(new OrderRequest("UMA","SELL",1,"MARKET",null),seller);
            assertEquals("FILLED",result.status());filled.incrementAndGet();
        } catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("수량"));}});
        assertEquals(10,filled.get());
        assertEquals(0,db.queryForObject("SELECT quantity FROM portfolios WHERE user_id=?",Integer.class,seller));
        assertEquals(100000,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,seller));
    }
    @Test void timePrioritySurvivesConcurrentTakers() throws Exception {
        long seller=user(0);shares(seller,"UMA",20);
        for(int i=0;i<20;i++) first.order(new OrderRequest("UMA","SELL",1,"LIMIT",10000L),seller);
        var expected=db.queryForList("SELECT id FROM orders WHERE user_id=? ORDER BY created_at,id",Long.class,seller);
        List<Long> buyers=new ArrayList<>();for(int i=0;i<20;i++)buyers.add(user(20000));
        parallel(20,i->assertEquals("FILLED",(i%2==0?first:second).order(new OrderRequest("UMA","BUY",1,"MARKET",null),buyers.get(i)).status()));
        assertEquals(expected,db.queryForList("SELECT sell_order_id FROM trades WHERE seller_id=? ORDER BY id",Long.class,seller));
    }
    @Test void rollbackRestoresReservationsAndDoesNotPublishACommittedOrder() {
        long buyer=user(100000);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
            first.order(new OrderRequest("UMA","BUY",3,"LIMIT",9900L),buyer);throw new IllegalStateException("rollback");
        }));
        assertEquals(100000,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?",Integer.class,buyer));
        assertEquals("OPEN",second.order(new OrderRequest("UMA","BUY",3,"LIMIT",9900L),buyer).status());
    }
    @Test void concurrentReadsAndMaintenanceRefundExpiredOrdersExactlyOnce() throws Exception {
        long buyer=user(100000);
        first.order(new OrderRequest("UMA","BUY",3,"LIMIT",9900L),buyer);
        db.update("UPDATE orders SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE user_id=?",buyer);
        assertTrue(first.orderBook("UMA").bids().isEmpty());
        parallel(24,i->{
            if(i%3==0) (i%2==0?first:second).maintainMarket();
            else {first.portfolio(buyer);second.activeOrders(buyer);second.orderHistory("UMA",buyer);}
        });
        assertEquals(100000,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND status='OPEN'",Integer.class,buyer));
    }
    @Test void unrelatedSymbolProgressesWhileAnotherTransactionIsUncommitted() throws Exception {
        long a=user(100000),b=user(100000);var pool=Executors.newFixedThreadPool(2);
        var holding=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var pending=pool.submit(()->tx.executeWithoutResult(s->{
                first.order(new OrderRequest("UMA","BUY",1,"LIMIT",9900L),a);holding.countDown();
                try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertTrue(holding.await(5,TimeUnit.SECONDS));
            var other=pool.submit(()->second.order(new OrderRequest("BA","BUY",1,"LIMIT",9900L),b));
            assertEquals("OPEN",other.get(5,TimeUnit.SECONDS).status());
            release.countDown();pending.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void peerTradeDoesNotWaitForAnUnreachableLpAccount() throws Exception {
        first.liquidityBotAction("UMA","BOTH");first.liquidityBotAction("BA","BOTH");
        long buyer=user(100000),seller=user(0);shares(seller,"UMA",1);
        first.order(new OrderRequest("UMA","SELL",1,"LIMIT",10000L),seller);
        long lp=db.queryForObject("SELECT id FROM users WHERE username='liquidity_provider'",Long.class);
        var pool=Executors.newFixedThreadPool(2);var holding=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var otherSymbol=pool.submit(()->tx.executeWithoutResult(s->{
                db.queryForObject("SELECT id FROM market_locks WHERE id=1 FOR SHARE",Integer.class);
                db.queryForObject("SELECT id FROM stocks WHERE stock_code='BA' FOR UPDATE",Long.class);
                db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,lp);holding.countDown();
                try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertTrue(holding.await(5,TimeUnit.SECONDS));
            assertEquals("FILLED",pool.submit(()->second.order(new OrderRequest("UMA","BUY",1,"MARKET",null),buyer)).get(5,TimeUnit.SECONDS).status());
            release.countDown();otherSymbol.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
