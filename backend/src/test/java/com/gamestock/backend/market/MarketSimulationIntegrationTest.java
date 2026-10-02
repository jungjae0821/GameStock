package com.gamestock.backend.market;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gamestock.backend.market.MarketModels.*;

/** Opt-in disposable MySQL test. Never connects to the application datasource or reads .env. */
@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:13367/.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MarketSimulationIntegrationTest {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private MarketService market;
    private DataSourceTransactionManager manager;
    private TransactionStatus transaction;
    private String database;
    private long buyer,seller,stock;

    @BeforeAll void createDisposableDatabase() throws Exception {
        dataSource=new SingleConnectionDataSource(System.getProperty("market.test.jdbc"),"root","",true);
        jdbc=new JdbcTemplate(dataSource);
        database="simulation_test_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        jdbc.execute("USE "+database);
        String schema=Files.readString(Path.of("../database/schema.sql"));
        schema=schema.replaceAll("(?m)^--.*$","").replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
        for(String statement:schema.split(";")) if(!statement.isBlank()) jdbc.execute(statement);
        market=new MarketService(event->{},jdbc,new UserFeatureService(jdbc),new TradingProtectionService(jdbc));
        ReflectionTestUtils.setField(market,"configuredSeed","12345");
        market.initializeData();
        market.maintainMarket();
        jdbc.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES ('test_buyer','TEST','test buyer',10000000),('test_seller','TEST','test seller',10000000)");
        buyer=id("test_buyer"); seller=id("test_seller");
        stock=jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code='UMA'",Long.class);
        jdbc.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price) VALUES (?,?,1000,1000,10000)",seller,stock);
        manager=new DataSourceTransactionManager(dataSource);
    }
    @BeforeEach void begin(){ transaction=manager.getTransaction(new DefaultTransactionDefinition()); }
    @AfterEach void rollback(){ if(transaction!=null) manager.rollback(transaction); }
    @AfterAll void dispose(){ if(jdbc!=null && database!=null) jdbc.execute("DROP DATABASE "+database); if(dataSource!=null) dataSource.destroy(); }
    private long id(String username){return jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username);}
    private void limit(long user,String side,int quantity,long price){market.order(new OrderRequest("UMA",side,quantity,"LIMIT",price),user);}

    @Test @Order(1) void compactBotLedgerKeepsActualFillsMetricsChartsAndHumanRecords() {
        jdbc.update("UPDATE users SET password_hash='TRADER' WHERE id IN (?,?)",buyer,seller);
        long now=System.currentTimeMillis();var journal=new BotLedgerJournal(jdbc);
        var repository=new BatchMarketRepository(jdbc,journal);var book=repository.load(now,Set.of(stock),Set.of(buyer,seller));
        book.submit(seller,stock,"SELL","LIMIT",5,10000,now,60000);
        book.submit(seller,stock,"SELL","LIMIT",10,10010,now,60000);
        book.submit(buyer,stock,"BUY","MARKET",7,0,now,0);
        repository.persist(book,now);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trades",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM settlements",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE stock_id=?",Integer.class,stock));
        var batch=BotLedgerJournal.decode(jdbc.queryForObject("SELECT payload FROM bot_ledger_batches WHERE stock_id=?",byte[].class,stock));
        assertEquals(List.of(5,2),batch.fills().stream().map(BotLedgerJournal.Execution::quantity).toList());
        assertEquals(7,batch.positions().stream().filter(p->p.user()==buyer).findFirst().orElseThrow().holding().quantity());
        for(var cash:batch.accounts())assertEquals(cash.after(),jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,cash.user()));
        var metrics=new PriceMetricService(jdbc).read(stock,10010,now+10,0);
        assertEquals(7,metrics.volume());assertEquals((50000+20020)/7.0,metrics.vwap(),.0001);
        assertEquals(2,market.publicTrades("UMA").size());assertFalse(market.priceHistory("UMA","5m").isEmpty());
        assertTrue(market.chartHistory("UMA","1h").stream().anyMatch(c->c.highPrice()==10010));
        assertEquals(7,market.priceDrivers("UMA").botBuyVolume());
        // A user can consume a surviving compact-origin quote through the unchanged API.
        long human=jdbc.queryForObject("SELECT id FROM users WHERE username='demo'",Long.class);
        market.order(new OrderRequest("UMA","BUY",1,"MARKET",null),human);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE buyer_id=?",Integer.class,human));
        assertEquals(1,market.orderHistory("UMA",human).size());assertEquals(1,market.settlements(human).size());
        var restarted=new BatchMarketRepository(jdbc,journal).load(now+20,Set.of(stock),Set.of(buyer,seller,human));
        assertEquals(7,restarted.holding(buyer,stock).quantity());assertEquals(1,restarted.holding(human,stock).quantity());
        assertEquals(7,restarted.working(seller,stock).get(0).remaining);
        // Retention changes only newly introduced bot detail; cumulative statistics and user rows survive.
        jdbc.update("UPDATE bot_ledger_batches SET created_at=?",new java.sql.Timestamp(now-7200000));
        journal.retain(now,60);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM bot_ledger_batches",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT fills FROM bot_ledger_totals WHERE stock_id=?",Integer.class,stock));
        assertEquals(1,market.settlements(human).size());assertEquals(3,market.publicTrades("UMA").size());
        assertTrue(market.chartHistory("UMA","1h").stream().anyMatch(c->c.highPrice()==10010));
    }
    @Test @Order(1) void compactLedgerRollsBackAndLpBudgetIncludesItsRealCashDelta() {
        jdbc.update("UPDATE users SET password_hash='TRADER' WHERE id=?",buyer);
        long lp=id("liquidity_provider"),now=System.currentTimeMillis();
        MarketMakerEngine.RiskBook before=ReflectionTestUtils.invokeMethod(market,"liquidityRiskBook","UMA",lp);
        long beforeCash=jdbc.queryForObject("SELECT SUM(cash) FROM users",Long.class);
        Object point=transaction.createSavepoint();
        var repository=new BatchMarketRepository(jdbc,new BotLedgerJournal(jdbc));var book=repository.load(now,Set.of(stock),Set.of(buyer,lp));
        book.submit(lp,stock,"SELL","LIMIT",2,10000,now,60000);book.submit(buyer,stock,"BUY","MARKET",2,0,now,0);repository.persist(book,now);
        MarketMakerEngine.RiskBook after=ReflectionTestUtils.invokeMethod(market,"liquidityRiskBook","UMA",lp);
        assertEquals(before.cashBudget()+19980,after.cashBudget());
        long fees=jdbc.queryForObject("SELECT SUM(fees) FROM bot_ledger_totals",Long.class);
        assertEquals(beforeCash,jdbc.queryForObject("SELECT SUM(cash) FROM users",Long.class)+fees);
        transaction.rollbackToSavepoint(point);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM bot_ledger_batches",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM bot_trade_seconds",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM bot_trade_tape",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM bot_account_fees",Integer.class));
        assertEquals(beforeCash,jdbc.queryForObject("SELECT SUM(cash) FROM users",Long.class));
        assertEquals(400,jdbc.queryForObject("SELECT quantity FROM portfolios WHERE user_id=? AND stock_id=?",Integer.class,lp,stock));
    }
    @Test @Order(1) void compactRetentionKeepsLongRangeOhlcAndLatestTapeAfterFineDetailExpires() {
        jdbc.update("UPDATE users SET password_hash='TRADER' WHERE id IN (?,?)",buyer,seller);
        long now=System.currentTimeMillis(),at=now-java.time.Duration.ofDays(10).toMillis();
        var journal=new BotLedgerJournal(jdbc);var repository=new BatchMarketRepository(jdbc,journal);
        var book=repository.load(at,Set.of(stock),Set.of(buyer,seller));
        book.submit(seller,stock,"SELL","LIMIT",2,10000,at,60000);
        book.submit(seller,stock,"SELL","LIMIT",3,10010,at,60000);
        book.submit(buyer,stock,"BUY","MARKET",5,0,at,0);
        repository.persist(book,at);
        journal.retain(now,15);
        for(String table:List.of("bot_ledger_batches","bot_trade_seconds","bot_trade_minutes"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM bot_trade_hours",Integer.class));
        assertEquals(2,journal.recent(stock).size());
        assertEquals(2,jdbc.queryForObject("SELECT fills FROM bot_ledger_totals WHERE stock_id=?",Integer.class,stock));
        var month=market.chartHistory("UMA","1m");
        assertTrue(month.stream().anyMatch(c->c.openPrice()==10000 && c.closePrice()==10010 && c.highPrice()==10010 && c.lowPrice()==10000 && c.volume()==2));
        assertTrue(market.chartHistory("UMA","1y").stream().anyMatch(c->c.highPrice()==10010));
    }

    @Test @Order(1) void batchOrdersShareTheRealLedgerAndRefundExactly() {
        jdbc.update("UPDATE users SET password_hash='TRADER' WHERE id IN (?,?)",buyer,seller);
        long cashBefore=jdbc.queryForObject("SELECT SUM(cash) FROM users",Long.class);
        long sharesBefore=jdbc.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class);
        BatchMarketRepository repository=new BatchMarketRepository(jdbc);
        long now=System.currentTimeMillis();
        var book=repository.load(now);
        book.submit(seller,stock,"SELL","LIMIT",10,10000,now,5000);
        book.submit(seller,stock,"SELL","LIMIT",15,10010,now,5000);
        book.submit(seller,stock,"SELL","LIMIT",20,10020,now,5000);
        var marketOrder=book.submit(buyer,stock,"BUY","MARKET",30,0,now,0);
        var cancelled=book.submit(buyer,stock,"BUY","LIMIT",2,9900,now,200);
        book.cancel(cancelled);
        repository.persist(book,now);
        assertEquals("FILLED",marketOrder.status);
        assertEquals(List.of(10,15,5),jdbc.queryForList("SELECT quantity FROM trades ORDER BY id",Integer.class));
        assertEquals(List.of(10000L,10010L,10020L),jdbc.queryForList("SELECT price FROM trades ORDER BY id",Long.class));
        assertEquals(30,market.portfolio(buyer).positions().get(0).quantity());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM settlements WHERE status='SETTLED'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trades t JOIN orders o ON t.buy_order_id=o.id WHERE o.user_id<>t.buyer_id",Integer.class));
        long cashAfter=jdbc.queryForObject("SELECT (SELECT SUM(cash) FROM users)+(SELECT COALESCE(SUM(reserved_cash),0) FROM orders WHERE status='OPEN')",Long.class);
        long fees=jdbc.queryForObject("SELECT SUM(fee) FROM trades",Long.class);
        assertEquals(cashBefore,cashAfter+fees);
        assertEquals(sharesBefore,jdbc.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class));
        // Individual user API can consume the remaining batched quote without stale book state.
        market.order(new OrderRequest("UMA","BUY",1,"MARKET",null),buyer);
        assertEquals(31,market.portfolio(buyer).positions().get(0).quantity());
    }

    @Test @Order(1) void batchRollbackRestoresOrdersCashAndHoldings() {
        jdbc.update("UPDATE users SET password_hash='TRADER' WHERE id IN (?,?)",buyer,seller);
        Object savepoint=transaction.createSavepoint();
        long cash=jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer);
        var repository=new BatchMarketRepository(jdbc);long now=System.currentTimeMillis();
        var book=repository.load(now);
        book.submit(seller,stock,"SELL","LIMIT",10,10000,now,5000);
        book.submit(buyer,stock,"BUY","MARKET",10,0,now,0);
        repository.persist(book,now);
        transaction.rollbackToSavepoint(savepoint);
        assertEquals(cash,jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,buyer));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trades",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders",Integer.class));
        assertEquals(1000,jdbc.queryForObject("SELECT quantity FROM portfolios WHERE user_id=? AND stock_id=?",Integer.class,seller,stock));
    }

    @Test @Order(1) void batchedAndIndividualMatchingHaveIdenticalEconomicResults() {
        // Exercise matching without the public per-user submission-rate guard.
        jdbc.update("UPDATE users SET password_hash='BOT' WHERE id IN (?,?)",buyer,seller);
        jdbc.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price) VALUES (?,?,1000,1000,10000)",buyer,stock);
        record Intent(long user,String side,String type,int quantity,long price) { }
        List<Intent> intents=new ArrayList<>();Random random=new Random(91);
        for(int i=0;i<180;i++)intents.add(new Intent(random.nextBoolean()?buyer:seller,random.nextBoolean()?"BUY":"SELL",random.nextInt(4)==0?"MARKET":"LIMIT",1+random.nextInt(12),9980+random.nextInt(5)*10));
        Object point=transaction.createSavepoint();
        for(Intent intent:intents)market.order(new OrderRequest("UMA",intent.side(),intent.quantity(),intent.type(),intent.type().equals("MARKET")?null:intent.price()),intent.user());
        var expected=economicState();
        transaction.rollbackToSavepoint(point);
        var repository=new BatchMarketRepository(jdbc);long now=System.currentTimeMillis();var book=repository.load(now);
        for(Intent intent:intents)assertNotNull(book.submit(intent.user(),stock,intent.side(),intent.type(),intent.quantity(),intent.price(),now++,10000));
        repository.persist(book,now);
        assertEquals(expected,economicState());
    }
    private List<Object> economicState() {
        return List.of(
                jdbc.queryForList("SELECT cash FROM users WHERE id IN (?,?) ORDER BY id",buyer,seller),
                jdbc.queryForList("SELECT user_id,stock_id,quantity,settled_quantity,average_price,realized_profit_loss FROM portfolios WHERE user_id IN (?,?) ORDER BY user_id,stock_id",buyer,seller),
                jdbc.queryForList("SELECT user_id,side,order_type,price,quantity,remaining_quantity,reserved_cash,reserved_quantity,status FROM orders ORDER BY id"),
                jdbc.queryForList("SELECT buyer_id,seller_id,aggressor_side,quantity,price,buyer_fee,seller_fee FROM trades ORDER BY id"),
                jdbc.queryForList("SELECT buyer_quantity_before,buyer_settled_quantity_before,buyer_average_price_before,buyer_realized_profit_loss_before,seller_quantity_before,seller_settled_quantity_before,seller_average_price_before,seller_realized_profit_loss_before FROM settlements ORDER BY id"),
                jdbc.queryForList("SELECT current_price,previous_price,total_volume FROM stocks WHERE id=?",stock));
    }
    @Test @Order(1) void batchCannotFundAnEarlierBuyWithLaterSaleProceedsAfterConcurrentSpending() {
        jdbc.update("UPDATE users SET password_hash='BOT',cash=10010 WHERE id=?",buyer);
        jdbc.update("UPDATE users SET password_hash='BOT' WHERE id=?",seller);
        jdbc.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES ('batch_third','BOT','batch third',100000)");
        long third=id("batch_third");
        var repository=new BatchMarketRepository(jdbc);long now=System.currentTimeMillis();var book=repository.load(now);
        book.submit(seller,stock,"SELL","LIMIT",1,10000,now,5000);
        book.submit(buyer,stock,"BUY","LIMIT",1,10000,now+1,5000);
        book.submit(third,stock,"BUY","LIMIT",1,10050,now+2,5000);
        book.submit(buyer,stock,"SELL","LIMIT",1,10050,now+3,5000);
        assertTrue(book.accounts.get(buyer).cash>10010,"net cash alone would appear sufficient");
        jdbc.update("UPDATE users SET cash=cash-500 WHERE id=?",buyer);
        assertThrows(org.springframework.dao.CannotAcquireLockException.class,()->repository.persist(book,now));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trades",Integer.class));
    }

    @Test @Order(1) void largeMarketOrderWalksLevelsAndPartiallyFillsLastLevel() {
        limit(seller,"SELL",10,10000); limit(seller,"SELL",15,10010); limit(seller,"SELL",20,10020);
        var result=market.order(new OrderRequest("UMA","BUY",30,"MARKET",null),buyer);
        assertEquals("FILLED",result.status());
        assertEquals(List.of(10,15,5),jdbc.queryForList("SELECT quantity FROM trades WHERE stock_id=? ORDER BY id",Integer.class,stock));
        assertEquals(List.of(10000L,10010L,10020L),jdbc.queryForList("SELECT price FROM trades WHERE stock_id=? ORDER BY id",Long.class,stock));
        assertEquals(15,jdbc.queryForObject("SELECT remaining_quantity FROM orders WHERE user_id=? AND status='OPEN'",Integer.class,seller));
        assertEquals(30,market.portfolio(buyer).positions().get(0).quantity());
        assertEquals(10020,market.stocks().stream().filter(s->s.code().equals("UMA")).findFirst().orElseThrow().price());
    }
    @Test @Order(1) void equalPricesRespectTimeAndSelfTradesArePrevented() {
        limit(seller,"SELL",5,10000); limit(seller,"SELL",7,10000);
        market.order(new OrderRequest("UMA","BUY",8,"MARKET",null),buyer);
        assertEquals(List.of(5,3),jdbc.queryForList("SELECT quantity FROM trades ORDER BY id",Integer.class));
        limit(buyer,"SELL",2,10000);
        market.order(new OrderRequest("UMA","BUY",20,"MARKET",null),buyer);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE buyer_id=seller_id",Integer.class));
    }

    @Test @Order(1) void slippageConsumesOnlyAffordableSharesIncludingRoundedFees() {
        jdbc.update("UPDATE users SET cash=30030 WHERE id=?",buyer);
        limit(seller,"SELL",1,10000); limit(seller,"SELL",10,10010);
        var partial=market.order(new OrderRequest("UMA","BUY",3,"MARKET",null),buyer);
        assertEquals("PARTIAL",partial.status());
        assertEquals(List.of(1,1),jdbc.queryForList("SELECT quantity FROM trades ORDER BY id",Integer.class));
        assertEquals(10000,market.portfolio(buyer).cash());
        // Exactly one share plus the rounded fee must be affordable (no floating point underfill).
        jdbc.update("UPDATE users SET cash=10020 WHERE id=?",buyer);
        market.order(new OrderRequest("UMA","BUY",1,"MARKET",null),buyer);
        assertEquals(0,market.portfolio(buyer).cash());
    }
    @Test @Order(1) void ttlReturnsCashAndReplaceDoesNotLeakReservations() {
        long before=market.portfolio(buyer).cash();
        limit(buyer,"BUY",10,9900);
        assertTrue(market.portfolio(buyer).cash()<before);
        jdbc.update("UPDATE orders SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE user_id=?",buyer);
        market.maintainMarket();
        assertEquals(before,market.portfolio(buyer).cash());
        market.liquidityBotAction("UMA","BOTH");
        long lp=id("liquidity_provider");
        long cash=jdbc.queryForObject("SELECT cash+COALESCE((SELECT SUM(reserved_cash) FROM orders WHERE user_id=? AND status='OPEN'),0) FROM users WHERE id=?",Long.class,lp,lp);
        for(int i=0;i<5;i++) market.liquidityBotAction("UMA","BOTH");
        assertEquals(cash,jdbc.queryForObject("SELECT cash+COALESCE((SELECT SUM(reserved_cash) FROM orders WHERE user_id=? AND status='OPEN'),0) FROM users WHERE id=?",Long.class,lp,lp));
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND status='OPEN'",Integer.class,lp));
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(DISTINCT CONCAT(side,price)) FROM orders WHERE user_id=? AND status='OPEN'",Integer.class,lp));
    }
    @Test @Order(1) void partialLimitFillsConserveEveryWonAcrossFeeRounding() {
        jdbc.update("UPDATE users SET cash=501001 WHERE id=?",buyer);
        long initial=jdbc.queryForObject("SELECT SUM(cash) FROM users",Long.class);
        limit(buyer,"BUY",50,10010);
        market.order(new OrderRequest("UMA","SELL",20,"MARKET",null),seller);
        market.order(new OrderRequest("UMA","SELL",30,"MARKET",null),seller);
        assertEquals(1,market.portfolio(buyer).cash());
        long total=jdbc.queryForObject("SELECT (SELECT SUM(cash) FROM users)+(SELECT COALESCE(SUM(reserved_cash),0) FROM orders WHERE status='OPEN')+(SELECT SUM(buyer_fee+seller_fee) FROM trades)",Long.class);
        assertEquals(initial,total);
    }

    @Test @Order(1) void splitFeesCannotOverdrawACompletelyReservedAccount() {
        jdbc.update("UPDATE users SET cash=1402801 WHERE id=?",buyer);
        limit(buyer,"BUY",140,10010);
        market.order(new OrderRequest("UMA","SELL",70,"MARKET",null),seller);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE cash<0",Integer.class));
        assertEquals(1402801,market.portfolio(buyer).cash());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trades",Integer.class));
    }
    @Test @Order(1) void lpBudgetsRemainIsolatedAfterARealFill() {
        long lp=id("liquidity_provider");
        MarketMakerEngine.RiskBook before=ReflectionTestUtils.invokeMethod(market,"liquidityRiskBook","BA",lp);
        market.liquidityBotAction("UMA","BOTH");
        market.order(new OrderRequest("UMA","SELL",5,"MARKET",null),seller);
        MarketMakerEngine.RiskBook after=ReflectionTestUtils.invokeMethod(market,"liquidityRiskBook","BA",lp);
        assertEquals(before,after);
        MarketMakerEngine.RiskBook changed=ReflectionTestUtils.invokeMethod(market,"liquidityRiskBook","UMA",lp);
        assertEquals(405,changed.inventory());
    }
    @Test @Order(1) void lpReplacesBothSidesAtBandBoundaryWithoutChangingLastPrice() {
        jdbc.update("UPDATE stocks SET current_price=11990 WHERE id=?",stock);
        for(int action=0;action<5;action++) market.liquidityBotAction("UMA","BOTH");
        long lp=id("liquidity_provider");
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND stock_id=? AND status='OPEN'",Integer.class,lp,stock));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND stock_id=? AND status='OPEN' AND (price<8000 OR price>12000)",Integer.class,lp,stock));
        assertEquals(11990,jdbc.queryForObject("SELECT current_price FROM stocks WHERE id=?",Long.class,stock));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trades",Integer.class));
    }
    @Test @Order(1) void patternsAndNewsNeverWriteTheLastPriceWithoutTrades() {
        var before=market.stocks();
        jdbc.update("INSERT INTO market_events(stock_id,event_type,title,description,impact,source) VALUES (?,'NEWS','우마무스메 대규모 업데이트','우마무스메 신규 업데이트',8,'test')",stock);
        for(int i=0;i<10;i++) market.maintainMarket();
        PatternEngine patterns=(PatternEngine)ReflectionTestUtils.getField(market,"patterns");
        for(long time=System.currentTimeMillis();time<System.currentTimeMillis()+1000000;time+=250000) patterns.environment("UMA",time);
        assertEquals(before,market.stocks());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trades",Integer.class));
        assertTrue(jdbc.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?",Double.class,stock)>10000);
    }
    @Test @Order(1) void realBotActionsMaintainFundsInventoryAndApiShapes() {
        for(String code:market.botStockCodes()) market.liquidityBotAction(code,"BOTH");
        for(int round=0;round<3;round++) for(String name:market.participantBotUsernames()) market.participantBotAction(name);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE cash<0",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM portfolios WHERE quantity<0 OR settled_quantity<0",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE buyer_id=seller_id",Integer.class));
        assertFalse(market.ranking().isEmpty()); assertNotNull(market.snapshot());
        assertEquals(20,market.participantBotUsernames().size());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM trades",Integer.class)>0);
    }
    @Test @Order(1) void botReviewPreservesOtherSymbolsAndTheirReservations() {
        String username="trader_bot_03"; long bot=id(username);
        var codes=market.botStockCodes();
        String keptCode=codes.get(4);
        market.order(new OrderRequest(keptCode,"BUY",2,"LIMIT",9900L),bot);
        var before=jdbc.queryForMap("SELECT id,expires_at,reserved_cash,remaining_quantity FROM orders WHERE user_id=? AND status='OPEN'",bot);
        @SuppressWarnings("unchecked") var cursors=(Map<String,Integer>)ReflectionTestUtils.getField(market,"researchCursors");
        cursors.put(username,0); // Review only symbols 0..2, not the existing order's symbol 4.
        market.participantBotAction(username);
        assertEquals(before,jdbc.queryForMap("SELECT id,expires_at,reserved_cash,remaining_quantity FROM orders WHERE id=? AND status='OPEN'",before.get("id")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id=? AND status='OPEN'",Integer.class,before.get("id")));
    }
    @Test @Order(1) void botPendingBuysNeverExceedPositionOrCashLimitsAcrossRepeatedReviews() {
        for(String code:market.botStockCodes()) market.liquidityBotAction(code,"BOTH");
        for(int round=0;round<20;round++) for(String name:List.of("trader_bot_03","trader_bot_07","trader_bot_11")) market.participantBotAction(name);
        for(BotProfile p:BotProfile.defaults(12345)) {
            long bot=id(p.username());
            for(String code:market.botStockCodes()) {
                int exposure=jdbc.queryForObject("SELECT COALESCE((SELECT quantity FROM portfolios WHERE user_id=? AND stock_id=s.id),0)+COALESCE((SELECT SUM(remaining_quantity) FROM orders WHERE user_id=? AND stock_id=s.id AND side='BUY' AND status='OPEN'),0) FROM stocks s WHERE stock_code=?",Integer.class,bot,bot,code);
                assertTrue(exposure<=p.positionLimit(),p.username()+":"+code);
            }
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE cash<0",Integer.class));
    }
    @Test @Order(1) void flowBotsAcquireDistributedInventoryOnlyByPayingForRealFills() {
        long initialShares=jdbc.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class);
        long initialCash=jdbc.queryForObject("SELECT SUM(cash) FROM users",Long.class);
        for(String code:market.botStockCodes()) market.liquidityBotAction(code,"BOTH");
        for(int round=0;round<12;round++) for(String name:List.of("trader_bot_14","trader_bot_19","trader_bot_16")) market.participantBotAction(name);
        assertTrue(jdbc.queryForObject("SELECT COUNT(DISTINCT stock_id) FROM trades",Integer.class)>=5);
        assertTrue(jdbc.queryForObject("SELECT SUM(quantity) FROM portfolios WHERE user_id IN (SELECT id FROM users WHERE username IN ('trader_bot_14','trader_bot_19','trader_bot_16'))",Long.class)>0);
        assertEquals(initialShares,jdbc.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class));
        assertEquals(initialCash,jdbc.queryForObject("SELECT (SELECT SUM(cash) FROM users)+(SELECT COALESCE(SUM(reserved_cash),0) FROM orders WHERE status='OPEN')+(SELECT COALESCE(SUM(buyer_fee+seller_fee),0) FROM trades)",Long.class));
    }
    @Test @Order(1) void participantExecutesTheSelectedIntentWithoutRedrawingItsSignal() {
        String name="trader_bot_19"; long user=id(name);
        for(String code:market.botStockCodes()) market.liquidityBotAction(code,"BOTH");
        MarketService planning=org.mockito.Mockito.spy(market);
        List<MarketService.BotObservation> selected=new ArrayList<>();
        org.mockito.Mockito.doAnswer(call->{
            MarketService.BotObservation result=(MarketService.BotObservation)call.callRealMethod();
            selected.add(result);return result;
        }).when(planning).selectBotObservation(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyList());
        for(int i=0;i<10;i++) planning.participantBotAction(name);
        for(var observation:selected) {
            assertEquals(observation.score(),observation.decision().score());
        }
        org.mockito.ArgumentCaptor<OrderRequest> placed=org.mockito.ArgumentCaptor.forClass(OrderRequest.class);
        org.mockito.Mockito.verify(planning,org.mockito.Mockito.atLeastOnce()).order(placed.capture(),org.mockito.ArgumentMatchers.eq(user));
        assertEquals(selected.stream().filter(o->o.decision().quantity()>0 && (o.resting().isEmpty()||o.replace())).count(),placed.getAllValues().size());
        int index=0;
        for(var o:selected) if(o.decision().quantity()>0 && (o.resting().isEmpty()||o.replace())) {
            OrderRequest actual=placed.getAllValues().get(index++);
            assertEquals(o.code(),actual.stockCode()); assertEquals(o.decision().side(),actual.side());
            assertEquals(o.decision().quantity(),actual.quantity());
        }
    }
    @Test @Order(1) void newsInterruptsRotationButStillHonorsReactionLatency() {
        var profile=BotProfile.defaults(12345).stream().filter(p->p.strategy()==BotProfile.Strategy.NEWS_REACTOR).findFirst().orElseThrow();
        var codes=market.botStockCodes(); String catalyst=codes.get(4);
        var stock=market.stocks().stream().filter(s->s.code().equals(catalyst)).findFirst().orElseThrow();
        jdbc.update("INSERT INTO market_events(stock_id,event_type,title,description,impact,source,created_at) SELECT id,'NEWS',?,?,9,'test',DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 10 SECOND) FROM stocks WHERE stock_code=?",
                stock.name()+" 매출 급증",stock.name()+" 대규모 업데이트 흥행",catalyst);
        @SuppressWarnings("unchecked") var cursors=(Map<String,Integer>)ReflectionTestUtils.getField(market,"researchCursors");
        cursors.put(profile.username(),0);
        Object observation=ReflectionTestUtils.invokeMethod(market,"selectBotObservation",profile,id(profile.username()),codes);
        assertEquals(catalyst,ReflectionTestUtils.invokeMethod(observation,"code"));
        jdbc.update("UPDATE market_events SET created_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 5 SECOND) WHERE source='test'");
        cursors.put(profile.username(),0);
        observation=ReflectionTestUtils.invokeMethod(market,"selectBotObservation",profile,id(profile.username()),codes);
        assertNotEquals(catalyst,ReflectionTestUtils.invokeMethod(observation,"code"));
    }
    @Test @Order(1) void markPriceDrivesPortfolioAndRankingWithExistingContracts() {
        limit(seller,"SELL",10,10000);
        market.order(new OrderRequest("UMA","BUY",10,"MARKET",null),buyer);
        jdbc.update("UPDATE market_price_metrics SET mark_price=9990 WHERE stock_id=?",stock);
        assertEquals(99900,market.portfolio(buyer).assetValue());
        assertEquals(99900,market.ranking().stream().filter(r->r.nickname().equals("test buyer")).findFirst().orElseThrow().assetValue());
    }

    @Test @Order(1) void denseHistoryRetainsTheWholeWindowAndExactVwap() {
        // Seed a historical feed fixture; the live matcher is tested separately above.
        limit(seller,"SELL",1,10000);
        market.order(new OrderRequest("UMA","BUY",1,"MARKET",null),buyer);
        long now=jdbc.queryForObject("SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP)*1000",Long.class);
        var ids=jdbc.queryForMap("SELECT buy_order_id,sell_order_id FROM trades ORDER BY id LIMIT 1");
        jdbc.update("UPDATE trades SET created_at=? WHERE stock_id=?",new java.sql.Timestamp(now-550000),stock);
        List<PriceMetricService.Trade> expected=new ArrayList<>();
        expected.add(new PriceMetricService.Trade(now-550000,10000,1));
        List<Object[]> rows=new ArrayList<>();
        for(int i=0;i<3600;i++) {
            long time=now-360000+(i/10)*1000L,price=9900+(i%31)*10L;int quantity=1+i%3;
            expected.add(new PriceMetricService.Trade(time,price,quantity));
            rows.add(new Object[]{stock,ids.get("buy_order_id"),ids.get("sell_order_id"),buyer,seller,quantity,price,new java.sql.Timestamp(time),ids.get("sell_order_id"),ids.get("buy_order_id")});
        }
        jdbc.batchUpdate("INSERT INTO trades(stock_id,buy_order_id,sell_order_id,buyer_id,seller_id,quantity,price,created_at,maker_order_id,taker_order_id,aggressor_side) VALUES (?,?,?,?,?,?,?,?,?,?,'BUY')",rows);
        var actual=new PriceMetricService(jdbc).read(stock,10000,now,0);
        assertEquals(PriceMetricService.calculate(expected,10000,10000,10000,0,0,now),actual);
        assertTrue(actual.return300s()!=0);
    }

    @Test @Order(2) void restartRetainsDepletedInventoryBudgetsAndNewsState() {
        market.liquidityBotAction("UMA","BOTH");
        market.order(new OrderRequest("UMA","BUY",5,"MARKET",null),buyer);
        long lp=id("liquidity_provider");
        long cashBefore=jdbc.queryForObject("SELECT cash+COALESCE((SELECT SUM(reserved_cash) FROM orders WHERE user_id=? AND status='OPEN'),0) FROM users WHERE id=?",Long.class,lp,lp);
        var allocations=jdbc.queryForList("SELECT * FROM lp_risk_books ORDER BY stock_id");
        manager.commit(transaction); transaction=null;
        // Run the actual startup path a second time, including idempotent schema upgrades.
        market.initializeData();
        assertEquals(cashBefore,jdbc.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,lp));
        assertEquals(395,jdbc.queryForObject("SELECT quantity FROM portfolios WHERE user_id=? AND stock_id=?",Integer.class,lp,stock));
        assertEquals(allocations,jdbc.queryForList("SELECT * FROM lp_risk_books ORDER BY stock_id"));
    }
}
