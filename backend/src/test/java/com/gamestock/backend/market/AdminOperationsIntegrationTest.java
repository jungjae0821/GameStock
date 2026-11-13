package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.auth.AuthService;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.gamestock.backend.market.AdminOperationsService.*;
import static com.gamestock.backend.market.MarketModels.*;

/** Only a new disposable schema on an explicitly selected loopback MySQL is touched. */
@EnabledIfEnvironmentVariable(named="ADMIN_TEST_JDBC",matches="jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminOperationsIntegrationTest {
    JdbcTemplate root,db;HikariDataSource source;String schema;
    MarketService market;AdminSupport support;AdminRuntimeControl runtime;
    AdminOperationsService operations;AdminContentService content;AdminSeasonService seasons;TitleService titles;AdminPromoService promos;
    TransactionTemplate transaction;long buyer,seller,admin,stock;
    @BeforeAll void start() throws Exception {
        String url=System.getenv("ADMIN_TEST_JDBC"),user=System.getenv("ADMIN_TEST_USER"),password=System.getenv("ADMIN_TEST_PASSWORD");
        root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="admin_test_"+UUID.randomUUID().toString().replace("-","");root.execute("CREATE DATABASE "+schema);
        HikariConfig config=new HikariConfig();config.setJdbcUrl(url.replaceFirst("/[^/?]*(\\?|$)","/"+schema+"$1"));config.setUsername(user);config.setPassword(password);config.setMaximumPoolSize(6);config.setConnectionInitSql("SET time_zone = '+00:00'");
        source=new HikariDataSource(config);db=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        String sql=Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$","").replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
        for(String statement:sql.split(";"))if(!statement.isBlank())db.execute(statement);
        var events=mock(ApplicationEventPublisher.class);var protection=new TradingProtectionService(db);
        market=new MarketService(events,db,new UserFeatureService(db),protection);
        titles=new TitleService(db,new DataSourceTransactionManager(source));market.configureTitles(titles);market.initializeData();
        runtime=new AdminRuntimeControl(db);market.configureAdminRuntime(runtime);protection.configureAdminRuntime(runtime);
        titles.configureAdminRuntime(runtime);
        support=new AdminSupport(db,mock(AuthService.class),new ObjectMapper().findAndRegisterModules());
        operations=new AdminOperationsService(db,support,market,runtime,events);content=new AdminContentService(db,support);seasons=new AdminSeasonService(db,support,operations,runtime);promos=new AdminPromoService(db,support);
        db.update("INSERT INTO users(username,password_hash,nickname,google_uid,role,cash) VALUES('admin-test','TEST','admin-test','admin-test','ADMIN',1000000),('buyer-test','TEST','buyer-test','buyer-test','USER',1000000),('seller-test','TEST','seller-test','seller-test','USER',1000000)");
        admin=id("admin-test");buyer=id("buyer-test");seller=id("seller-test");stock=db.queryForObject("SELECT id FROM stocks WHERE stock_code='UMA'",Long.class);
    }
    @BeforeEach void reset(){
        tx(()->{
            for(String table:List.of("derivative_trades","derivative_positions","derivative_expiry_fixings","derivative_index_observations","user_feature_limits","stock_comments","price_alerts","admin_season_entries","admin_seasons","user_reports","admin_content","admin_mission_config","admin_cash_epochs","admin_cash_adjustments","admin_permissions","admin_audit","settlements","trades","orders","portfolios","mission_rewards","attendance_rewards","promo_redemptions","user_titles"))db.update("DELETE FROM "+table);
            db.update("DELETE FROM stock_tags WHERE created_by IS NOT NULL");db.update("DELETE FROM admin_stock_state");db.update("UPDATE admin_runtime SET bots_paused=FALSE,traders_paused=FALSE,market_paused=FALSE,cadence_ms=NULL,target_per_symbol=NULL,trader_ramp_seconds=NULL,traders_resumed_at=NULL");
            db.update("UPDATE users SET cash=1000000,loan_balance=0,account_reset_at=NULL WHERE id IN (?,?)",buyer,seller);
            db.update("UPDATE stocks SET current_price=10000,previous_price=10000 WHERE id=?",stock);
            new TradingProtectionService(db).refreshDay();
            db.update("UPDATE stock_protection_state SET day_reference=10000,static_reference=10000,dynamic_reference=10000,last_trade_price=10000,vi_type=NULL WHERE stock_id=?",stock);
            db.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price) VALUES(?,?,100,100,10000)",seller,stock);return null;
        });
        DerivativeTapeFixture.seed(db,java.time.Instant.now());
    }
    @AfterAll void stop(){if(source!=null)source.close();if(root!=null&&schema!=null)root.execute("DROP DATABASE "+schema);}
    private long id(String name){return db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);}
    private <T>T tx(Supplier<T> task){return transaction.execute(status->task.get());}
    private long cash(long id){return db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,id);}
    private void pause(){tx(()->operations.runtime(admin,new RuntimeChange(true,false,true,500,20,null,"복구 점검")));}

    @Test void adminPromoCodePaysCashAndTitlesOnceAndHonoursItsLimit(){
        db.update("DELETE FROM promo_codes WHERE code<>'syuen'");
        var request=new AdminPromoService.Promo("Launch-1",200000,List.of("special_syuaga"),1,null,null,true,"오픈 기념","오픈 이벤트");
        tx(()->promos.create(admin,request));
        assertThrows(IllegalArgumentException.class,()->tx(()->promos.create(admin,request)));
        assertThrows(IllegalArgumentException.class,()->promos.create(admin,new AdminPromoService.Promo("empty",0,List.of(),null,null,null,true,"","보상 없음")));
        assertThrows(IllegalArgumentException.class,()->promos.create(admin,new AdminPromoService.Promo("weekly",0,List.of("weekly_1"),null,null,null,true,"","주간 칭호")));
        var paid=titles.redeem(buyer,"LAUNCH-1");
        // Promo cash goes to the personal wallet; the trading account and its outside-cash total stay as they were.
        assertEquals(200000,paid.rewardCash());assertEquals(1000000,cash(buyer));assertEquals(200000L,PersonalWallet.balance(db,buyer));
        assertTrue(paid.titles().stream().anyMatch(title->title.id().equals("special_syuaga")&&title.owned()));
        assertEquals(0L,runtime.externalCash(buyer));
        assertThrows(IllegalArgumentException.class,()->titles.redeem(buyer,"launch-1"));
        assertThrows(IllegalArgumentException.class,()->titles.redeem(seller,"launch-1"));
        tx(()->promos.update(admin,"launch-1",new AdminPromoService.Promo("ignored",200000,List.of("special_syuaga"),null,null,null,false,"","중지")));
        assertThrows(IllegalArgumentException.class,()->titles.redeem(seller,"launch-1"));
        var listed=promos.promos();
        @SuppressWarnings("unchecked") var codes=(List<Map<String,Object>>)listed.get("codes");
        assertEquals(1L,((Number)codes.stream().filter(row->"launch-1".equals(row.get("code"))).findFirst().orElseThrow().get("used_count")).longValue());
        assertEquals(1,promos.redemptions("launch-1").size());
        assertEquals(0,titles.redeem(seller,"syuen").rewardCash());
    }

    @Test void derivativeOnlyCollateralCanBeLiquidatedToRepayTheLoan() {
        var manager=new DataSourceTransactionManager(source);
        var derivatives=new DerivativeService(db,manager);
        transaction.executeWithoutResult(status->derivatives.open(buyer,new DerivativeModels.OrderRequest("ETF","BUY",100,"ETFIDX",null,null,null,null)));
        db.update("UPDATE users SET cash=0,loan_balance=500000 WHERE id=?",buyer);
        var loans=new LoanService(db,market,mock(OrderService.class),derivatives,mock(ApplicationEventPublisher.class),manager);
        long asset=loans.status(buyer).grossAsset();
        assertTrue(asset>0 && asset<500000);
        loans.liquidate(buyer);
        assertEquals(0,cash(buyer));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM derivative_positions WHERE user_id=? AND status='OPEN'",Long.class,buyer));
        assertTrue(loans.status(buyer).balance()<500000);
        long fees=db.queryForObject("SELECT SUM(fee) FROM derivative_trades WHERE user_id=? AND action='CLOSE'",Long.class,buyer);
        assertEquals(500000-asset+fees,loans.status(buyer).balance());
    }

    @ParameterizedTest @ValueSource(strings={"ETF","FUTURE","OPTION"})
    void collateralLiquidationPreservesUnneededDerivativeContracts(String kind) {
        var manager=new DataSourceTransactionManager(source);
        var derivatives=new DerivativeService(db,manager);
        derivatives.setClock(java.time.Clock.fixed(java.time.Instant.now(),java.time.ZoneOffset.UTC));
        db.update("UPDATE users SET cash=10000000 WHERE id=?",buyer);
        double index=derivatives.snapshot().indexValue();
        int quantity="ETF".equals(kind)?100:5;
        var request=switch(kind) {
            case "ETF" -> new DerivativeModels.OrderRequest(kind,"BUY",quantity,"ETFIDX",null,null,null,null);
            case "FUTURE" -> new DerivativeModels.OrderRequest(kind,"LONG",quantity,"FUTIDX",5,null,null,null);
            default -> new DerivativeModels.OrderRequest(kind,"CALL",quantity,null,null,"1H",index*0.98,null);
        };
        long position=transaction.execute(status->derivatives.open(buyer,request)).account().positions().get(0).id();
        long asset=db.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,position);
        db.update("UPDATE users SET cash=?,loan_balance=?,loan_interest_on=NULL WHERE id=?",asset*15/100,asset,buyer);
        var loans=new LoanService(db,market,mock(OrderService.class),derivatives,mock(ApplicationEventPublisher.class),manager);
        assertEquals(1.15,loans.status(buyer).collateralRatio(),0.0001);
        loans.liquidate(buyer);
        int remaining=db.queryForObject("SELECT quantity FROM derivative_positions WHERE id=?",Integer.class,position);
        assertTrue(remaining>0 && remaining<quantity,"Only the contracts needed for repayment should be sold");
        assertEquals("OPEN",db.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,position));
        var after=loans.status(buyer);
        assertTrue(after.balance()>0 && after.balance()<asset);
        assertTrue(after.grossAsset()*10>=after.balance()*14,"Repayment must restore the 140% ratio including fees");
        assertEquals(quantity-remaining,db.queryForObject("SELECT SUM(quantity) FROM derivative_trades WHERE position_id=? AND action='CLOSE'",Integer.class,position));
        long cash=after.cash(),debt=after.balance();
        loans.liquidate(buyer);
        assertEquals(remaining,db.queryForObject("SELECT quantity FROM derivative_positions WHERE id=?",Integer.class,position));
        assertEquals(cash,cash(buyer));assertEquals(debt,loans.status(buyer).balance());
    }

    @Test void repaymentWhileWaitingForTheDerivativeAccountLockPreventsForcedSale() throws Exception {
        var manager=new DataSourceTransactionManager(source);
        var normal=new DerivativeService(db,manager);
        long position=tx(()->normal.open(buyer,new DerivativeModels.OrderRequest("ETF","BUY",100,"ETFIDX",null,null,null,null)))
                .account().positions().get(0).id();
        long asset=db.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,position);
        long initialCash=asset*15/100;
        db.update("UPDATE users SET cash=?,loan_balance=?,loan_interest_on=NULL WHERE id=?",initialCash,asset,buyer);
        CountDownLatch locking=new CountDownLatch(1);
        JdbcTemplate waiting=new JdbcTemplate(source) {
            @Override public <T>T queryForObject(String sql,Class<T> type,Object... args) {
                if(sql.equals("SELECT id FROM users WHERE id = ? FOR UPDATE"))locking.countDown();
                return super.queryForObject(sql,type,args);
            }
        };
        var events=mock(ApplicationEventPublisher.class);
        var loans=new LoanService(db,market,mock(OrderService.class),new DerivativeService(waiting,manager),events,manager);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> pending=tx(()->{
                db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,buyer);
                Future<?> work=worker.submit(()->loans.liquidate(buyer));
                try {assertTrue(locking.await(5,TimeUnit.SECONDS));}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                assertFalse(work.isDone());
                db.update("UPDATE users SET cash=cash+? WHERE id=?",asset,buyer);
                loans.repay(buyer,asset);
                return work;
            });
            pending.get(5,TimeUnit.SECONDS);
            assertEquals(0,loans.status(buyer).balance());assertEquals(initialCash,cash(buyer));
            assertEquals(100,db.queryForObject("SELECT quantity FROM derivative_positions WHERE id=?",Integer.class,position));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM derivative_trades WHERE position_id=? AND action='CLOSE'",Integer.class,position));
            verifyNoInteractions(events);
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void lowerExecutionProceedsContinueLiquidationUntilCashIsSufficientOrThePositionIsExhausted() {
        transaction.executeWithoutResult(state->{
            var manager=new DataSourceTransactionManager(source);
            var derivatives=new DerivativeService(db,manager);
            long position=derivatives.open(buyer,new DerivativeModels.OrderRequest("ETF","BUY",100,"ETFIDX",null,null,null,null))
                    .account().positions().get(0).id();
            long asset=db.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,position);
            long initialCash=asset*15/100;
            db.update("UPDATE users SET cash=?,loan_balance=?,loan_interest_on=NULL WHERE id=?",initialCash,asset,buyer);
            // Stored NAV is unchanged, but the conservative execution price is now 20% lower.
            db.update("UPDATE stocks s JOIN derivative_reference_prices r ON r.stock_id=s.id SET s.current_price=FLOOR(r.price*0.8)");
            var loans=new LoanService(db,market,mock(OrderService.class),derivatives,mock(ApplicationEventPublisher.class),manager);
            loans.liquidate(buyer);
            assertEquals(0,db.queryForObject("SELECT quantity FROM derivative_positions WHERE id=?",Integer.class,position));
            assertEquals(0,cash(buyer));
            long proceeds=db.queryForObject("SELECT SUM(cash_flow) FROM derivative_trades WHERE position_id=? AND action='CLOSE'",Long.class,position);
            assertEquals(asset-initialCash-proceeds,loans.status(buyer).balance());
            assertTrue(loans.status(buyer).balance()>0,"An insolvent account retains its unpaid debt");
            state.setRollbackOnly();
        });
    }

    @ParameterizedTest @ValueSource(strings={"paused","invalidated","missingExpiryFixing"})
    void collateralSalesKeepTradingAndSettlementGuards(String reason) {
        var manager=new DataSourceTransactionManager(source);
        var derivatives=new DerivativeService(db,manager);
        var now=java.time.Instant.now();
        derivatives.setClock(java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
        boolean option="missingExpiryFixing".equals(reason);
        double index=derivatives.snapshot().indexValue();
        var request=option
                ? new DerivativeModels.OrderRequest("OPTION","CALL",5,null,null,"1H",index*0.98,null)
                : new DerivativeModels.OrderRequest("ETF","BUY",100,"ETFIDX",null,null,null,null);
        long position=tx(()->derivatives.open(buyer,request)).account().positions().get(0).id();
        long asset=db.queryForObject("SELECT mark_value FROM derivative_positions WHERE id=?",Long.class,position);
        long initialCash=asset*15/100;
        db.update("UPDATE users SET cash=?,loan_balance=?,loan_interest_on=NULL WHERE id=?",initialCash,asset,buyer);
        if("paused".equals(reason))db.update("UPDATE admin_runtime SET market_paused=TRUE WHERE id=1");
        else if("invalidated".equals(reason))db.update("UPDATE derivative_reference_prices SET observed_at=NULL");
        else derivatives.setClock(java.time.Clock.fixed(now.plusSeconds(3601),java.time.ZoneOffset.UTC));
        var loans=new LoanService(db,market,mock(OrderService.class),derivatives,mock(ApplicationEventPublisher.class),manager);
        loans.liquidate(buyer);
        assertEquals(request.quantity(),db.queryForObject("SELECT quantity FROM derivative_positions WHERE id=?",Integer.class,position));
        assertEquals("OPEN",db.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,position));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM derivative_trades WHERE position_id=? AND action<>'OPEN'",Integer.class,position));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM derivative_expiry_fixings",Integer.class));
        // Repaying existing cash remains possible; rejected asset sales cannot invent proceeds.
        assertEquals(0,cash(buyer));assertEquals(asset-initialCash,loans.status(buyer).balance());
    }

    @Test void weeklyRankingUsesTheSameDerivativeValuationAsThePortfolio() {
        var derivatives=new DerivativeService(db,new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status->derivatives.open(buyer,new DerivativeModels.OrderRequest("ETF","BUY",100,"ETFIDX",null,null,null,null)));
        var investors=(java.util.List<?>)org.springframework.test.util.ReflectionTestUtils.invokeMethod(titles,"investorAssets");
        var asset=investors.stream().filter(row->java.util.Objects.equals(org.springframework.test.util.ReflectionTestUtils.invokeMethod(row,"userId"),buyer)).findFirst().orElseThrow();
        assertEquals(market.portfolio(buyer).totalAsset(),(Long)org.springframework.test.util.ReflectionTestUtils.invokeMethod(asset,"adjustedAsset"));
    }

    @Test void seasonIncludesEtfsAndResetCannotCashOutAnOldPosition() {
        var derivatives=new DerivativeService(db,new DataSourceTransactionManager(source));
        var opened=transaction.execute(status->derivatives.open(buyer,new DerivativeModels.OrderRequest("ETF","BUY",100,"ETFIDX",null,null,null,null)));
        long position=opened.account().positions().get(0).id();
        long before=market.portfolio(buyer).totalAsset();
        long season=((Number)tx(()->seasons.create(admin,new AdminSeasonService.Create("asset-test","자산 검사"))).get("id")).longValue();
        tx(()->{seasons.start(admin,season,"시작");return null;});
        assertEquals(before,db.queryForObject("SELECT opening_asset FROM admin_season_entries WHERE season_id=? AND user_id=?",Long.class,season,buyer));
        transaction.executeWithoutResult(status->derivatives.close(buyer,position,100));
        tx(()->seasons.close(admin,season,new AdminSeasonService.Close(List.of(),"종료")));
        var row=db.queryForMap("SELECT opening_asset,closing_asset FROM admin_season_entries WHERE season_id=? AND user_id=?",season,buyer);
        assertTrue(((Number)row.get("closing_asset")).longValue()<=((Number)row.get("opening_asset")).longValue());
        long old=transaction.execute(status->derivatives.open(buyer,new DerivativeModels.OrderRequest("ETF","BUY",1,"ETFIDX",null,null,null,null))).account().positions().get(0).id();
        pause();
        tx(()->{seasons.reset(admin,season,"초기화");return null;});
        assertEquals(1_000_000,cash(buyer));
        assertEquals("RESET",db.queryForObject("SELECT status FROM derivative_positions WHERE id=?",String.class,old));
        assertThrows(IllegalArgumentException.class,()->transaction.execute(status->derivatives.close(buyer,old,1)));
        assertEquals(1_000_000,cash(buyer));
    }

    @Test void newsProjectionIsIdempotentAndRetainsCorrectedEconomicValueAfterExpiry() {
        var oldClock=org.springframework.test.util.ReflectionTestUtils.getField(market,"clock");
        var now=java.time.Instant.parse("2030-01-01T10:00:00Z");
        org.springframework.test.util.ReflectionTestUtils.setField(market,"clock",java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
        try {transaction.executeWithoutResult(status->{
            long tr=db.queryForObject("SELECT id FROM stocks WHERE stock_code='TR'",Long.class);
            db.update("DELETE FROM market_events WHERE stock_id=? AND event_type='NEWS'",tr);
            db.update("UPDATE market_price_metrics SET anchor_price=10000,hidden_fundamental=10000 WHERE stock_id=?",tr);
            db.update("DELETE FROM market_news_valuation WHERE stock_id=?",tr);
            db.update("DELETE FROM market_fundamental_state WHERE stock_id=?",tr);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(market,"updateFundamentalFromNews","TR");
            for(int i=0;i<10;i++) {
                db.update("INSERT INTO market_events(stock_id,event_type,title,description,impact,source,created_at,published_at) VALUES(?,'NEWS','트릭컬 업데이트','트릭컬 신규 콘텐츠',10,'regression',?,?)",tr,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(market,"updateFundamentalFromNews","TR");
            }
            double split=db.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?",Double.class,tr);
            db.update("UPDATE market_price_metrics SET hidden_fundamental=10000,last_news_id=0 WHERE stock_id=?",tr);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(market,"updateFundamentalFromNews","TR");
            assertEquals(split,db.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?",Double.class,tr),1e-9);
            db.update("UPDATE market_events SET impact=-10 WHERE stock_id=? AND source='regression'",tr);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(market,"updateFundamentalFromNews","TR");
            assertTrue(db.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?",Double.class,tr)<10000);
            db.update("UPDATE market_events SET published_at=? WHERE stock_id=? AND source='regression'",java.sql.Timestamp.from(now.minusSeconds(7*86400)),tr);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(market,"updateFundamentalFromNews","TR");
            double retained=db.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?",Double.class,tr);
            assertTrue(retained<10000,"Permanent negative economic impact survives the news window");
            db.update("DELETE FROM market_events WHERE stock_id=? AND source='regression'",tr);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(market,"updateFundamentalFromNews","TR");
            assertEquals(10000,db.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?",Double.class,tr),1e-7);
            status.setRollbackOnly();
        });}finally{org.springframework.test.util.ReflectionTestUtils.setField(market,"clock",oldClock);}
    }

    @Test void latestHumanFillAndSameMillisecondSequenceWinOverOlderBotBuckets() {
        var oldClock=org.springframework.test.util.ReflectionTestUtils.getField(market,"clock");
        long second=java.time.Instant.parse("2030-01-01T10:00:00Z").toEpochMilli();
        try {transaction.executeWithoutResult(status->{
            db.update("INSERT INTO bot_trade_seconds(stock_id,bucket_at,first_at,last_at,open_price,high_price,low_price,close_price,quantity,notional,fills,first_sequence,last_sequence) VALUES(?,?,?,?,10000,10000,10000,10000,1,10000,1,1,1)",stock,second/1000,second+100,second+100);
            org.springframework.test.util.ReflectionTestUtils.setField(market,"clock",java.time.Clock.fixed(java.time.Instant.ofEpochMilli(second+800),java.time.ZoneOffset.UTC));
            market.order(new OrderRequest("UMA","SELL",1,"LIMIT",10200L),seller);
            org.springframework.test.util.ReflectionTestUtils.setField(market,"clock",java.time.Clock.fixed(java.time.Instant.ofEpochMilli(second+900),java.time.ZoneOffset.UTC));
            market.order(new OrderRequest("UMA","BUY",1,"LIMIT",10200L),buyer);
            var metrics=new PriceMetricService(db);
            assertEquals(10200,metrics.read(stock,10200,second+950,0).lastPrice());
            assertEquals(second+900,db.queryForObject("SELECT created_at FROM trades WHERE stock_id=? ORDER BY id DESC LIMIT 1",java.sql.Timestamp.class,stock).getTime());
            db.update("UPDATE bot_trade_seconds SET last_at=? WHERE stock_id=?",second+900,stock);
            assertEquals(10200,metrics.read(stock,10200,second+950,0).lastPrice());
            var points=market.priceHistory("UMA","15m");
            assertEquals(10200,points.get(points.size()-1).price());
            status.setRollbackOnly();
        });}finally{org.springframework.test.util.ReflectionTestUtils.setField(market,"clock",oldClock);}
    }

    @Test void deniedAdminCallRollsBackAttendanceThroughTheActualTransactionProxy() throws Exception {
        var firebase=mock(com.google.firebase.auth.FirebaseAuth.class);
        var token=mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getUid()).thenReturn("buyer-test");
        when(firebase.verifyIdToken("synthetic",true)).thenReturn(token);
        try(var statics=mockStatic(com.google.firebase.auth.FirebaseAuth.class)) {
            statics.when(com.google.firebase.auth.FirebaseAuth::getInstance).thenReturn(firebase);
            var factory=new org.springframework.aop.framework.ProxyFactory(new AuthService(db));
            factory.setProxyTargetClass(true);
            factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    new DataSourceTransactionManager(source),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            AuthService proxy=(AuthService)factory.getProxy();
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->proxy.requireAdmin("Bearer synthetic"));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM attendance_rewards WHERE user_id=?",Integer.class,buyer));
            assertEquals(1_000_000,cash(buyer));
        }
    }

    @Test void schemaUpgradeFreezesExistingReferenceBeforeDailyMaintenance() {
        db.update("UPDATE stocks SET current_price=9000 WHERE id=?",stock);
        db.update("UPDATE stock_protection_state SET day_reference=10000 WHERE stock_id=?",stock);
        db.update("DELETE FROM derivative_index_bases WHERE stock_id=?",stock);
        var derivatives=new DerivativeService(db,new DataSourceTransactionManager(source));
        derivatives.ensureTables();
        assertEquals(10000,db.queryForObject("SELECT reference_price FROM derivative_index_bases WHERE stock_id=?",Long.class,stock));
        double before=derivatives.snapshot().indexValue();
        db.update("UPDATE stock_protection_state SET day_reference=9000 WHERE stock_id=?",stock);
        assertEquals(before,derivatives.snapshot().indexValue(),0);
    }

    @Test void legacyEtfCashFlowMigrationIsIdempotent() {
        var derivatives=new DerivativeService(db,new DataSourceTransactionManager(source));
        transaction.execute(status->derivatives.open(buyer,new DerivativeModels.OrderRequest("ETF","BUY",1,"ETFIDX",null,null,null,null)));
        long actual=db.queryForObject("SELECT cash_flow FROM derivative_trades WHERE user_id=?",Long.class,buyer);
        db.update("UPDATE derivative_trades SET cash_flow=cash_flow+fee,cash_flow_version=1 WHERE user_id=?",buyer);
        derivatives.ensureTables();derivatives.ensureTables();
        assertEquals(actual,db.queryForObject("SELECT cash_flow FROM derivative_trades WHERE user_id=?",Long.class,buyer));
        assertEquals(2,db.queryForObject("SELECT cash_flow_version FROM derivative_trades WHERE user_id=?",Integer.class,buyer));
    }

    @Test void featureWritesRespectAccountAndStockLimitsAcrossTransactions() {
        var features=new UserFeatureService(db);
        for(int i=0;i<10;i++){final int n=i;transaction.execute(status->features.addTag(buyer,"UMA",new UserFeatureModels.TagRequest("guard-"+n)));}
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->transaction.execute(status->features.addTag(buyer,"UMA",new UserFeatureModels.TagRequest("too-fast"))));
        assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM stock_tags WHERE created_by=?",Integer.class,buyer));
        db.update("UPDATE user_feature_limits SET window_no=0 WHERE user_id=?",buyer);
        var rows=new java.util.ArrayList<Object[]>();
        for(int i=0;i<100;i++)rows.add(new Object[]{stock,"quota-"+i,seller});
        db.batchUpdate("INSERT IGNORE INTO stock_tags(stock_id,tag,created_by) VALUES(?,?,?)",rows);
        assertThrows(IllegalArgumentException.class,()->transaction.execute(status->features.addTag(buyer,"UMA",new UserFeatureModels.TagRequest("over-stock-cap"))));
        assertEquals(100,features.tags("UMA").size());
    }

    @Test void cashAdjustmentIsIdempotentAndCannotOverdraw(){
        var request=new CashChange("incident-1",50000,"장애 보상");
        assertEquals(true,tx(()->operations.cash(admin,buyer,request)).get("applied"));
        assertEquals(false,tx(()->operations.cash(admin,buyer,request)).get("applied"));assertEquals(1050000,cash(buyer));
        assertThrows(IllegalArgumentException.class,()->tx(()->operations.cash(admin,seller,request)));
        assertThrows(IllegalArgumentException.class,()->tx(()->operations.cash(admin,buyer,new CashChange("negative",-2000000,"오류 차감"))));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM admin_cash_adjustments",Integer.class));
    }
    @Test void concurrentRetriesPayExactlyOnce() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{var request=new CashChange("parallel",100,"중복 요청");var a=pool.submit(()->tx(()->operations.cash(admin,buyer,request)));var b=pool.submit(()->tx(()->operations.cash(admin,buyer,request)));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(1000100,cash(buyer));}finally{pool.shutdownNow();}
    }
    @Test void auditFailureRollsBackBothCashAndIdempotencyReceipt(){
        ObjectMapper broken=mock(ObjectMapper.class);try{when(broken.writeValueAsString(any())).thenThrow(new IllegalArgumentException("audit failure"));}catch(Exception e){throw new RuntimeException(e);}
        var service=new AdminOperationsService(db,new AdminSupport(db,mock(AuthService.class),broken),market,runtime,mock(ApplicationEventPublisher.class));
        assertThrows(IllegalArgumentException.class,()->tx(()->service.cash(admin,buyer,new CashChange("rollback",100,"원자성 검사"))));
        assertEquals(1000000,cash(buyer));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM admin_cash_adjustments",Integer.class));
    }
    @Test void forcedOrderCancellationReturnsTheReservation(){
        tx(()->market.order(new OrderRequest("UMA","BUY",3,"LIMIT",9900L),buyer));assertTrue(cash(buyer)<1000000);
        long order=db.queryForObject("SELECT id FROM orders WHERE user_id=?",Long.class,buyer);
        tx(()->{operations.cancelOrder(admin,order,"미체결 복구");return null;});assertEquals(1000000,cash(buyer));
        assertEquals(0,db.queryForObject("SELECT reserved_cash FROM orders WHERE id=?",Long.class,order));
    }
    @Test void haltCancelsRestingOrdersAndBlocksActualMatchingUntilResumed(){
        tx(()->market.order(new OrderRequest("UMA","SELL",3,"LIMIT",10000L),seller));
        tx(()->operations.stockState(admin,"UMA",new StockState("HALTED","종목 점검")));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM orders WHERE status='OPEN'",Integer.class));
        assertThrows(IllegalArgumentException.class,()->tx(()->market.order(new OrderRequest("UMA","BUY",1,"LIMIT",10000L),buyer)));
        tx(()->operations.stockState(admin,"UMA",new StockState("ACTIVE","점검 완료")));
        assertEquals("OPEN",tx(()->market.order(new OrderRequest("UMA","BUY",1,"LIMIT",10000L),buyer)).status());
    }
    @Test void settlementReversalRestoresBothSidesAndFeesExactlyOnce(){
        tx(()->market.order(new OrderRequest("UMA","SELL",3,"LIMIT",10000L),seller));
        tx(()->market.order(new OrderRequest("UMA","BUY",3,"MARKET",null),buyer));
        long settlement=db.queryForObject("SELECT id FROM settlements",Long.class);assertEquals(969970,cash(buyer));assertEquals(1029970,cash(seller));pause();
        var execution=db.queryForObject("SELECT created_at FROM trades",java.sql.Timestamp.class).toInstant();
        var history=new DerivativeIndexHistory(db);
        history.observe(1000,execution.minusSeconds(1));history.observe(1100,execution.plusSeconds(6));
        tx(()->{operations.reverse(admin,settlement,"오체결 정정");return null;});
        assertNull(db.queryForObject("SELECT observed_at FROM derivative_reference_prices WHERE stock_id=?",java.sql.Timestamp.class,stock));
        assertTrue(db.queryForObject("SELECT invalidated FROM derivative_index_observations WHERE observed_at=?",Boolean.class,java.sql.Timestamp.from(execution.plusSeconds(6))));
        assertEquals(1000,history.fixing(execution.plusSeconds(7)));
        assertEquals(1000000,cash(buyer));assertEquals(1000000,cash(seller));
        assertEquals(100,db.queryForObject("SELECT quantity FROM portfolios WHERE user_id=? AND stock_id=?",Integer.class,seller,stock));
        assertEquals(0,db.queryForObject("SELECT quantity FROM portfolios WHERE user_id=? AND stock_id=?",Integer.class,buyer,stock));
        assertThrows(IllegalArgumentException.class,()->tx(()->{operations.reverse(admin,settlement,"중복 취소");return null;}));
    }
    @Test void preConversionSettlementCannotRestoreSnapshotsInTheOldShareUnits() {
        tx(()->market.order(new OrderRequest("UMA","SELL",3,"LIMIT",10000L),seller));
        tx(()->market.order(new OrderRequest("UMA","BUY",3,"MARKET",null),buyer));
        long settlement=db.queryForObject("SELECT id FROM settlements",Long.class);
        long trade=db.queryForObject("SELECT id FROM trades",Long.class);
        pause();
        db.update("INSERT INTO stock_denominations VALUES(?,'UMA',10000,20000,10000,20000,?,CURRENT_TIMESTAMP(3))",stock,trade);
        try {
            var failure=assertThrows(IllegalArgumentException.class,()->tx(()->{operations.reverse(admin,settlement,"환산 이전 체결 취소");return null;}));
            assertTrue(failure.getMessage().contains("환산 이전"));
            assertEquals(969970,cash(buyer));assertEquals(1029970,cash(seller));
            assertEquals("SETTLED",db.queryForObject("SELECT status FROM settlements WHERE id=?",String.class,settlement));
        } finally { db.update("DELETE FROM stock_denominations WHERE stock_id=?",stock); }
    }
    @Test void contentOnlyPublishesDuringItsVisibilityWindow(){
        tx(()->content.save(admin,null,new AdminContentService.Content("NOTICE","점검 안내","안내 본문",null,false,null,null,"초안")));
        tx(()->content.save(admin,null,new AdminContentService.Content("NEWS","종목 소식","소식 본문","UMA",true,null,null,"게시")));
        tx(()->content.save(admin,null,new AdminContentService.Content("EVENT","지난 이벤트","종료 본문",null,true,java.time.Instant.now().minusSeconds(100),java.time.Instant.now().minusSeconds(10),"종료")));
        assertEquals(3,content.content(false,null,0,50).size());assertEquals(1,content.content(true,null,0,50).size());
    }
    @Test void noticeKeepsImageAndButtonsButRejectsUnsafeLinks(){
        var saved=tx(()->content.save(admin,null,new AdminContentService.Content("NOTICE","새 시즌","본문",null,true,null,null,"게시","/notice/season.png","지금 보기","/market"," 다음에 ")));
        assertEquals("/notice/season.png",saved.get("image_url"));assertEquals("지금 보기",saved.get("action_label"));assertEquals("/market",saved.get("action_url"));assertEquals("다음에",saved.get("dismiss_label"));
        assertThrows(IllegalArgumentException.class,()->tx(()->content.save(admin,null,new AdminContentService.Content("NOTICE","제목","본문",null,true,null,null,"게시",null,"열기","javascript:alert(1)",null))));
        assertThrows(IllegalArgumentException.class,()->tx(()->content.save(admin,null,new AdminContentService.Content("NOTICE","제목","본문",null,true,null,null,"게시","//evil.example/a.png",null,null,null))));
        assertThrows(IllegalArgumentException.class,()->tx(()->content.save(admin,null,new AdminContentService.Content("NOTICE","제목","본문",null,true,null,null,"게시",null,"열기",null,null))));
    }
    @Test void reportsAreDeduplicatedAndResolutionIsAudited(){
        long id=((Number)tx(()->content.report(buyer,new AdminContentService.Report(seller,"반복 거래 의심"))).get("id")).longValue();
        assertThrows(IllegalArgumentException.class,()->tx(()->content.report(buyer,new AdminContentService.Report(seller,"중복 신고"))));
        tx(()->{content.resolve(admin,id,new AdminContentService.Resolution("DISMISSED","확인 결과 정상 거래","검토 완료"));return null;});
        assertEquals(1,content.reports("DISMISSED",0,50).size());assertEquals(1,operations.audit(admin,"REPORT_RESOLVE",0,50).size());
    }
    @Test void missionConfigurationChangesTheActualRewardAndDuplicateClaimsPayNothing(){
        String mission=MarketService.dailyMissionIds(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))).stream().filter(x->!x.equals("watch")).findFirst().orElseThrow();
        long cashBefore=cash(buyer),walletBefore=PersonalWallet.balance(db,buyer);
        tx(()->{content.mission(admin,mission,new AdminContentService.Mission(true,1234,0,"보상 조정"));return null;});
        assertEquals(1234,tx(()->market.rewardMission(mission,buyer)).rewardCash());
        assertEquals(0,tx(()->market.rewardMission(mission,buyer)).rewardCash());
        assertEquals(cashBefore,cash(buyer));assertEquals(walletBefore+1234,PersonalWallet.balance(db,buyer));
        tx(()->{content.mission(admin,mission,new AdminContentService.Mission(false,1234,0,"미션 중지"));return null;});
        assertThrows(IllegalArgumentException.class,()->tx(()->market.rewardMission(mission,seller)));
    }
    @Test void seasonSnapshotsRewardsAndResetHaveExplicitStateTransitions(){
        long season=((Number)tx(()->seasons.create(admin,new AdminSeasonService.Create("시즌 1","시즌 생성"))).get("id")).longValue();
        tx(()->{seasons.start(admin,season,"시작");return null;});
        assertThrows(IllegalArgumentException.class,()->tx(()->{seasons.start(admin,season,"중복 시작");return null;}));
        tx(()->operations.cash(admin,buyer,new CashChange("outside",100000,"시즌 외부 보상")));
        var ranking=tx(()->seasons.close(admin,season,new AdminSeasonService.Close(List.of(1000L),"시즌 종료")));assertEquals(2,ranking.size());
        assertEquals(buyer,((Number)ranking.get(0).get("user_id")).longValue());
        assertThrows(IllegalArgumentException.class,()->tx(()->{seasons.reset(admin,season,"초기화");return null;}));pause();
        tx(()->{seasons.reset(admin,season,"새 시즌 준비");return null;});assertEquals(1000000,cash(buyer));assertEquals(1000000,cash(seller));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM portfolios WHERE user_id IN (?,?)",Integer.class,buyer,seller));
        assertEquals(2,operations.audit(admin,"SEASON_USER_RESET",0,50).size());
        assertEquals(0,runtime.externalCash(buyer));assertEquals(0,market.portfolio(buyer).adminAdjustmentCash());
    }
    @Test void listQueriesAndStockRegistrationUseRealMysql(){
        for(String reserved:List.of("NEXA","STAR","MOMO","VOID"))assertThrows(IllegalArgumentException.class,()->tx(()->operations.createStock(admin,new StockCreate(reserved,"예약 종목","개발사","RPG",10000,"등록 검사"))));
        operations.dashboard();operations.user(buyer);operations.orders(null,null,null,0,50);operations.trades(null,null,0,50);operations.settlements(null,null,0,50);operations.suspicious(24,2);operations.legacyAudit(null,0,50);
        String code="T"+System.nanoTime()%100000000;
        var priorBaskets=new DerivativeBaskets(db).load();
        var created=tx(()->operations.createStock(admin,new StockCreate(code,"새 게임","개발사","RPG",10000,"신규 종목")));
        var createdBaskets=new DerivativeBaskets(db).load();
        for(var entry:priorBaskets.baskets().entrySet())assertEquals(entry.getValue().price(),createdBaskets.basket(entry.getKey()).price(),1e-8);
        assertNotNull(created.get("id"));tx(()->operations.updateStock(admin,code,new StockChange("수정 게임","개발사","전략","정보 수정")));
        for(var entry:priorBaskets.baskets().entrySet())assertEquals(entry.getValue().price(),new DerivativeBaskets(db).load().basket(entry.getKey()).price(),1e-8);
        assertEquals("OPEN",tx(()->market.order(new OrderRequest(code,"BUY",1,"LIMIT",9900L),buyer)).status());
        StockCatalog.ensureListings(db);AdminSchema.restoreStockMetadata(db);
        assertEquals("수정 게임",operations.stocks().stream().filter(x->code.equals(x.get("stock_code"))).findFirst().orElseThrow().get("name"));
    }
    @Test void botPauseWithdrawsExistingQuotesAndKeepsHumanTradingAvailable(){
        long bot=db.queryForObject("SELECT id FROM users WHERE password_hash='BOT' ORDER BY id LIMIT 1",Long.class);
        db.update("UPDATE users SET cash=990000 WHERE id=?",bot);
        db.update("INSERT INTO orders(user_id,stock_id,side,order_type,price,quantity,remaining_quantity,reserved_cash,reserved_quantity,status) VALUES(?,?,'BUY','LIMIT',10000,1,1,10000,0,'OPEN')",bot,stock);
        tx(()->operations.runtime(admin,new RuntimeChange(true,false,false,1000,10,null,"봇만 정지")));
        assertEquals(1000000,cash(bot));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND status='OPEN'",Integer.class,bot));
        assertEquals("OPEN",tx(()->market.order(new OrderRequest("UMA","BUY",1,"LIMIT",9900L),buyer)).status());
    }
    @Test void traderOnlyPauseKeepsLiquidityQuotesAndResumeRampsTradersBackIn(){
        long lp=db.queryForObject("SELECT id FROM users WHERE password_hash='BOT' ORDER BY id LIMIT 1",Long.class);
        long trader=db.queryForObject("SELECT id FROM users WHERE password_hash='TRADER' ORDER BY id LIMIT 1",Long.class);
        for(long owner:List.of(lp,trader))db.update("INSERT INTO orders(user_id,stock_id,side,order_type,price,quantity,remaining_quantity,reserved_cash,reserved_quantity,status) VALUES(?,?,'BUY','LIMIT',10000,1,1,0,0,'OPEN')",owner,stock);
        var paused=tx(()->operations.runtime(admin,new RuntimeChange(false,true,false,null,null,300,"트레이더만 정지")));
        assertTrue(paused.tradersStopped());assertFalse(paused.botsPaused());assertEquals(0,paused.traderShare());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND status='OPEN'",Integer.class,lp));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND status='OPEN'",Integer.class,trader));
        var resumed=tx(()->operations.runtime(admin,new RuntimeChange(false,false,false,null,null,300,"트레이더 재가동")));
        assertTrue(resumed.traderShare()>0&&resumed.traderShare()<.1,"ramp starts small: "+resumed.traderShare());
        db.update("UPDATE admin_runtime SET traders_resumed_at=CURRENT_TIMESTAMP(3)-INTERVAL 301 SECOND");
        assertEquals(1,runtime.settings().traderShare());
    }
    @Test void bulkRewardsAndAbsoluteHoldingRecoveryAreAuditedAndCannotRepeat(){
        tx(()->operations.rewardAll(admin,new CashChange("all-test",100,"전체 보상")));assertEquals(1000100,cash(buyer));assertEquals(1000100,cash(seller));assertEquals(1000000,cash(admin));
        assertThrows(IllegalArgumentException.class,()->tx(()->operations.rewardAll(admin,new CashChange("all-test",100,"전체 보상"))));
        tx(()->operations.holding(admin,buyer,new HoldingChange("restore-1","UMA",5,10000,"누락 주식 복구")));
        assertEquals(5,db.queryForObject("SELECT quantity FROM portfolios WHERE user_id=? AND stock_id=?",Integer.class,buyer,stock));
        assertThrows(IllegalArgumentException.class,()->tx(()->operations.holding(admin,buyer,new HoldingChange("restore-1","UMA",5,10000,"누락 주식 복구"))));
    }
    @Test void externalRewardsDoNotCountAsTradingReturns(){
        double before=market.ranking().stream().filter(x->x.nickname().equals("buyer-test")).findFirst().orElseThrow().changePercent();
        tx(()->operations.cash(admin,buyer,new CashChange("neutral",100000,"장애 보상")));
        assertEquals(before,market.ranking().stream().filter(x->x.nickname().equals("buyer-test")).findFirst().orElseThrow().changePercent());
        assertEquals(100000,market.portfolio(buyer).adminAdjustmentCash());
    }
    @Test void delegatedPermissionsAreStoredAndRevokedWithoutChangingPlayerRole(){
        tx(()->{operations.permissions(admin,buyer,new Grants(Set.of("READ","CONTENT"),"콘텐츠 담당"));return null;});assertEquals(2,operations.permissions().size());
        tx(()->{operations.permissions(admin,buyer,new Grants(Set.of(),"권한 회수"));return null;});assertTrue(operations.permissions().isEmpty());
        assertEquals("USER",db.queryForObject("SELECT role FROM users WHERE id=?",String.class,buyer));
    }
    @Test void recoveringAMissingMissionMarksItClaimedAndCannotPayItAgain(){
        var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        String mission=MarketService.dailyMissionIds(today).stream().filter(x->!x.equals("watch")).findFirst().orElseThrow();
        // Yesterday: other tests in this shared schema may already have paid today's mission.
        var request=new MissionRecovery(mission,today.minusDays(1),50000,"누락 미션 복구");
        long cashBefore=cash(buyer),walletBefore=PersonalWallet.balance(db,buyer);
        assertEquals(true,tx(()->operations.recoverMission(admin,buyer,request)).get("awarded"));
        assertEquals(false,tx(()->operations.recoverMission(admin,buyer,request)).get("awarded"));
        assertEquals(cashBefore,cash(buyer));assertEquals(walletBefore+50000,PersonalWallet.balance(db,buyer));
        assertEquals(0,market.portfolio(buyer).adminAdjustmentCash());
    }
}
