package com.gamestock.backend.market;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise real transaction, rounding and projection behavior on a disposable local database. */
@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class StockDenominationMigrationIntegrationTest {
    private SingleConnectionDataSource source;
    private JdbcTemplate db;
    private MarketService market;
    private String schema;
    private long kd,gi,holder,fraction,seller;
    private final Instant now=Instant.now();
    private final Map<String,Long> targets=Map.of("KD",100L,"GI",20000L);

    @BeforeEach void setup() throws Exception {
        source=new SingleConnectionDataSource(System.getProperty("market.test.jdbc"),"root","",true);
        db=new JdbcTemplate(source);schema="denomination_"+UUID.randomUUID().toString().replace("-","");
        db.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        db.execute("USE "+schema);db.execute("SET time_zone='+00:00'");
        String ddl=Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$","")
                .replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
        for(String statement:ddl.split(";"))if(!statement.isBlank())db.execute(statement);
        var protection=new TradingProtectionService(db);
        market=new MarketService(event->{},db,new UserFeatureService(db),protection);
        market.initializeData();
        kd=stock("KD");gi=stock("GI");
        db.update("UPDATE stocks SET current_price=10000,previous_price=9000,denomination_version=0 WHERE id IN (?,?)",kd,gi);
        db.update("UPDATE market_price_metrics SET mark_price=10000,anchor_price=10000,hidden_fundamental=10000 WHERE stock_id IN (?,?)",kd,gi);
        db.update("UPDATE stock_protection_state SET day_reference=10000,static_reference=10000,dynamic_reference=10000,last_trade_price=10000 WHERE stock_id IN (?,?)",kd,gi);
        db.update("UPDATE stock_price_history SET price=10000 WHERE stock_id IN (?,?)",kd,gi);
        db.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES('holder','TEST','holder',900000),('fraction','TEST','fraction',1000000),('seller','TEST','seller',1000000)");
        holder=user("holder");fraction=user("fraction");seller=user("seller");
        db.update("INSERT INTO portfolios(user_id,stock_id,quantity,settled_quantity,average_price,realized_profit_loss) VALUES(?,?,10,10,8000,300),(?,?,1,1,8000,50),(?,?,5,5,12700,-200)",holder,kd,holder,gi,fraction,gi);
        db.update("INSERT INTO orders(user_id,stock_id,side,order_type,price,quantity,remaining_quantity,status,reserved_cash,reserved_quantity) VALUES(?,?,'BUY','LIMIT',10000,10,10,'PARTIAL',100000,0),(?,?,'SELL','LIMIT',10000,1,1,'OPEN',0,1)",holder,gi,holder,gi);
        DerivativeTapeFixture.seed(db,now);
    }

    @AfterEach void cleanup() {
        if(db!=null && schema!=null)db.execute("DROP DATABASE "+schema);
        if(source!=null)source.destroy();
    }

    private long stock(String code) { return db.queryForObject("SELECT id FROM stocks WHERE stock_code=?",Long.class,code); }
    private long user(String name) { return db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name); }
    private long value(String sql,Object... args) { return db.queryForObject(sql,Long.class,args); }
    private Map<String,Object> position(long user,long stock) {
        return db.queryForMap("SELECT quantity,settled_quantity,average_price,realized_profit_loss FROM portfolios WHERE user_id=? AND stock_id=?",user,stock);
    }
    private void apply() { new StockDenominationMigration(db).apply(targets,()->ReflectionTestUtils.invokeMethod(market,"settleDuePayments"),now); }
    private long trade(long stock,int quantity,String status) {
        db.update("INSERT INTO orders(user_id,stock_id,side,order_type,price,quantity,remaining_quantity,status) VALUES(?,?,'BUY','LIMIT',10000,?,0,'FILLED')",holder,stock,quantity);
        long buy=value("SELECT MAX(id) FROM orders");
        db.update("INSERT INTO orders(user_id,stock_id,side,order_type,price,quantity,remaining_quantity,status) VALUES(?,?,'SELL','LIMIT',10000,?,0,'FILLED')",seller,stock,quantity);
        long sell=value("SELECT MAX(id) FROM orders");
        db.update("INSERT INTO trades(stock_id,buy_order_id,sell_order_id,buyer_id,seller_id,maker_order_id,taker_order_id,aggressor_side,quantity,price,created_at) VALUES(?,?,?,?,?,?,?,'BUY',?,10000,?)",stock,buy,sell,holder,seller,sell,buy,quantity,Timestamp.from(now.minusSeconds(10)));
        long trade=value("SELECT MAX(id) FROM trades");
        db.update("INSERT INTO settlements(trade_id,buyer_id,seller_id,stock_id,quantity,gross_amount,seller_fee,settlement_at,status) VALUES(?,?,?,?,?,?,10,?,?)",trade,holder,seller,stock,quantity,quantity*10000L,Timestamp.from(now.plusSeconds(100)),status);
        return trade;
    }

    @Test void convertsHoldingsRefundsOrdersAndPreservesDerivativesAndOriginalExecutions() {
        long trade=trade(kd,3,"SETTLED");
        var raw=db.queryForMap("SELECT price,quantity FROM trades WHERE id=?",trade);
        var baskets=new DerivativeBaskets(db).load();
        long fresh=value("SELECT current_price FROM stocks WHERE stock_code='GKM'");
        apply();
        assertEquals(Map.of("quantity",1000,"settled_quantity",1000,"average_price",80L,"realized_profit_loss",300L),position(holder,kd));
        assertEquals(Map.of("quantity",0,"settled_quantity",0,"average_price",0L,"realized_profit_loss",2050L),position(holder,gi));
        assertEquals(Map.of("quantity",2,"settled_quantity",2,"average_price",25400L,"realized_profit_loss",-2900L),position(fraction,gi));
        assertEquals(1010000,value("SELECT cash FROM users WHERE id=?",holder));
        assertEquals(1010000,value("SELECT cash FROM users WHERE id=?",fraction));
        assertEquals(0,value("SELECT COUNT(*) FROM orders WHERE stock_id IN (?,?) AND status IN ('OPEN','PARTIAL')",kd,gi));
        assertEquals(2,value("SELECT COUNT(*) FROM stock_denomination_orders WHERE user_id=?",holder));
        assertEquals(raw,db.queryForMap("SELECT price,quantity FROM trades WHERE id=?",trade));
        assertEquals(.01,db.queryForObject("SELECT price_scale FROM trades WHERE id=?",Double.class,trade),1e-12);
        assertEquals(30000,value("SELECT gross_amount FROM settlements WHERE trade_id=?",trade));
        assertEquals(100,value("SELECT MIN(price) FROM stock_price_history WHERE stock_id=?",kd));
        assertEquals(18000,value("SELECT previous_price FROM stocks WHERE id=?",gi));
        assertEquals(fresh,value("SELECT current_price FROM stocks WHERE stock_code='GKM'"));
        assertEquals(0,value("SELECT COUNT(*) FROM stock_denominations WHERE stock_code='GKM'"));
        assertEquals(100000,db.queryForObject("SELECT notional FROM bot_trade_seconds WHERE stock_id=? LIMIT 1",Double.class,kd));
        assertEquals(1000,db.queryForObject("SELECT quantity FROM bot_trade_seconds WHERE stock_id=? LIMIT 1",Double.class,kd));
        var metrics=new PriceMetricService(db);
        assertEquals(100,metrics.read(kd,100,now.toEpochMilli(),0).referenceVwap(),1e-8);
        metrics.project(kd,now.toEpochMilli());
        assertEquals(100,metrics.read(kd,100,now.toEpochMilli(),0).referenceVwap(),1e-8);
        assertEquals(20000,metrics.read(gi,20000,now.toEpochMilli(),0).referenceVwap(),1e-8);
        new DerivativePriceOracle(db).refresh(now.plusSeconds(1));
        new DerivativeBaskets(db).rebalance(now.plusSeconds(1));
        var after=new DerivativeBaskets(db).load();
        for(String code:baskets.baskets().keySet()) {
            assertEquals(baskets.basket(code).price(),after.basket(code).price(),1e-7,code);
            assertEquals(baskets.basket(code).previous(),after.basket(code).previous(),1e-7,code);
        }
        long cash=value("SELECT cash FROM users WHERE id=?",holder),audits=value("SELECT COUNT(*) FROM stock_denomination_positions");
        apply();
        assertEquals(cash,value("SELECT cash FROM users WHERE id=?",holder));
        assertEquals(audits,value("SELECT COUNT(*) FROM stock_denomination_positions"));
        assertEquals(2,value("SELECT COUNT(*) FROM stock_denominations"));
    }

    @Test void legacyPendingSettlementIsCompletedBeforeConvertingItsShares() {
        long trade=trade(gi,1,"PENDING");
        db.update("UPDATE portfolios SET settled_quantity=0 WHERE user_id=? AND stock_id=?",holder,gi);
        apply();
        assertEquals("SETTLED",db.queryForObject("SELECT status FROM settlements WHERE trade_id=?",String.class,trade));
        assertEquals(1009990,value("SELECT cash FROM users WHERE id=?",seller));
        assertEquals(0,((Number)position(holder,gi).get("quantity")).intValue());
        apply();
        assertEquals(1009990,value("SELECT cash FROM users WHERE id=?",seller));
    }

    @Test void failureAfterCashWritesRollsBackEverythingAndRetryPaysOnce() {
        JdbcTemplate failing=new JdbcTemplate(source) {
            @Override public int update(String sql,Object... args) {
                int result=super.update(sql,args);
                if(sql.startsWith("UPDATE stock_price_history"))throw new IllegalStateException("Injected projection failure");
                return result;
            }
        };
        assertThrows(IllegalStateException.class,()->new StockDenominationMigration(failing).apply(targets,()->{},now));
        assertEquals(900000,value("SELECT cash FROM users WHERE id=?",holder));
        assertEquals(1,((Number)position(holder,gi).get("quantity")).intValue());
        assertEquals(0,value("SELECT COUNT(*) FROM stock_denominations"));
        assertEquals(0,value("SELECT COUNT(*) FROM stock_denomination_positions"));
        assertEquals(0,value("SELECT COUNT(*) FROM stock_denomination_orders"));
        assertEquals(2,value("SELECT COUNT(*) FROM stocks WHERE denomination_version=0"));
        assertEquals(2,value("SELECT COUNT(*) FROM orders WHERE user_id=? AND status IN ('OPEN','PARTIAL')",holder));
        apply();
        assertEquals(1010000,value("SELECT cash FROM users WHERE id=?",holder));
    }

    @Test void twoInstancesSerializeTheMigrationAndDoNotPayTwice() throws Exception {
        String url=System.getProperty("market.test.jdbc").replaceFirst("/[^/?]*(\\?|$)","/"+schema+"$1");
        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            var work=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++)work.add(pool.submit(()->{
                try { assertTrue(start.await(5,TimeUnit.SECONDS)); }
                catch(InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                var independent=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));
                new StockDenominationMigration(independent).apply(targets,()->{},now);
            }));
            start.countDown();
            for(Future<?> task:work)task.get(15,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(1010000,value("SELECT cash FROM users WHERE id=?",holder));
        assertEquals(2,value("SELECT COUNT(*) FROM stock_denominations"));
    }

    @Test void schemaUpgradeMarksOnlyExistingStocksAndSurvivesDdlRetry() {
        db.execute("ALTER TABLE stocks DROP COLUMN denomination_version");
        StockDenominationMigration.ensureTables(db);
        assertEquals(20,value("SELECT COUNT(*) FROM stocks WHERE denomination_version=0"));
        StockDenominationMigration.ensureTables(db);
        assertEquals(20,value("SELECT COUNT(*) FROM stocks WHERE denomination_version=0"));
        db.update("INSERT INTO stocks(game_id,stock_code,current_price,previous_price) SELECT game_id,'NEW',100,100 FROM stocks WHERE id=?",kd);
        assertEquals(1,value("SELECT denomination_version FROM stocks WHERE stock_code='NEW'"));
    }

    @Test void usesPortfolioMarkInsteadOfLastPrintAndHandlesNonIntegerRatio() {
        db.update("UPDATE stocks SET current_price=9900 WHERE id=?",gi);
        db.update("UPDATE market_price_metrics SET mark_price=9473 WHERE stock_id=?",gi);
        long before=value("SELECT cash FROM users WHERE id=?",fraction)+5*9473;
        apply();
        long after=value("SELECT cash FROM users WHERE id=?",fraction)+value("SELECT quantity FROM portfolios WHERE user_id=? AND stock_id=?",fraction,gi)*20000;
        assertEquals(before,after);
        assertEquals(9473,value("SELECT cash_paid FROM stock_denomination_positions WHERE user_id=? AND stock_id=?",holder,gi));
        assertEquals(20000,value("SELECT mark_price FROM market_price_metrics WHERE stock_id=?",gi));
        assertNotEquals(20000,value("SELECT current_price FROM stocks WHERE id=?",gi));
        new DerivativePriceOracle(db).refresh(now.plusSeconds(1));
        assertEquals(10000.0*20000/9473,db.queryForObject("SELECT price FROM derivative_reference_prices WHERE stock_id=?",Double.class,gi),1e-7);
    }

    @Test void startupUsesCatalogTargetsAndRestartKeepsCashAndConvertedPositions() {
        market.initializeData();
        assertEquals(100,value("SELECT current_price FROM stocks WHERE id=?",kd));
        assertEquals(215000,value("SELECT current_price FROM stocks WHERE id=?",gi));
        assertEquals(1000,((Number)position(holder,kd).get("quantity")).intValue());
        assertEquals(0,((Number)position(holder,gi).get("quantity")).intValue());
        assertEquals(1010000,value("SELECT cash FROM users WHERE id=?",holder));
        assertEquals(1050000,value("SELECT cash FROM users WHERE id=?",fraction));
        market.initializeData();
        assertEquals(1010000,value("SELECT cash FROM users WHERE id=?",holder));
        assertEquals(1050000,value("SELECT cash FROM users WHERE id=?",fraction));
        assertEquals(1000,((Number)position(holder,kd).get("quantity")).intValue());
        assertEquals(2,value("SELECT COUNT(*) FROM stock_denominations"));
    }

    @Test void lpCashInLieuSurvivesProjectionRebuildAndNewTradesKeepTheirOwnUnits() {
        long lp=user("liquidity_provider");
        db.update("UPDATE portfolios SET quantity=1,settled_quantity=1,average_price=8000 WHERE user_id=? AND stock_id=?",lp,gi);
        long opening=value("SELECT opening_cash FROM lp_risk_books WHERE stock_id=?",gi);
        db.update("INSERT INTO lp_cash_projection(stock_id,last_id,cash) VALUES(?,0,?) ON DUPLICATE KEY UPDATE cash=VALUES(cash)",gi,opening);
        apply();
        assertEquals(opening+10000,value("SELECT cash FROM lp_cash_projection WHERE stock_id=?",gi));
        assertEquals(opening+10000,value("SELECT opening_cash FROM lp_risk_books WHERE stock_id=?",gi));
        db.update("DELETE FROM lp_cash_projection WHERE stock_id=?",gi);
        market.liquidity().refreshProjection(gi,lp);
        assertEquals(opening+10000,value("SELECT cash FROM lp_cash_projection WHERE stock_id=?",gi));
        long fresh=trade(kd,10,"SETTLED");
        db.update("UPDATE trades SET price=100 WHERE id=?",fresh);
        db.update("UPDATE settlements SET gross_amount=1000 WHERE trade_id=?",fresh);
        var metrics=new PriceMetricService(db);metrics.project(kd,now.toEpochMilli());
        assertEquals(1,db.queryForObject("SELECT price_scale FROM trades WHERE id=?",Double.class,fresh));
        assertEquals(100,metrics.read(kd,100,now.toEpochMilli(),0).referenceVwap(),1e-8);
    }
}
