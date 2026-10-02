package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** A synchronized Java/SQL clock exercises a full half-hour without sleeping or using production data. */
@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:13367/.*")
class MarketIdleSimulationTest {
    private static final class TestClock extends Clock {
        long now=Instant.parse("2026-10-02T00:00:00Z").toEpochMilli();
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
        public Instant instant(){return Instant.ofEpochMilli(now);}
        public long millis(){return now;}
    }
    private static final class CountedSource extends SingleConnectionDataSource {
        int acquisitions;
        CountedSource(String url){super(url,"root","",true);}
        @Override public Connection getConnection() throws SQLException {acquisitions++;return super.getConnection();}
    }
    private static void sqlClock(JdbcTemplate db,TestClock clock) {
        db.execute("SET timestamp="+String.format(Locale.ROOT,"%.3f",clock.now/1000.0));
    }
    @Test void halfHourlyPulseHasNoIdleDatabaseWorkAndWakeRefundsBeforeNormalTrading() throws Exception {
        String schema="idle_test_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getProperty("market.test.jdbc")+"&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        try(var source=new CountedSource(url)) {
            JdbcTemplate db=new JdbcTemplate(source);db.execute("CREATE DATABASE "+schema);db.execute("USE "+schema);
            try {
                String ddl=Files.readString(Path.of("../database/schema.sql")).replaceAll("(?m)^--.*$","")
                        .replaceAll("(?is)CREATE DATABASE[^;]+;","").replaceAll("(?is)USE gamestock;","");
                for(String statement:ddl.split(";"))if(!statement.isBlank())db.execute(statement);
                var clock=new TestClock();long start=clock.now;sqlClock(db,clock);
                var protection=new TradingProtectionService(db);ReflectionTestUtils.setField(protection,"clock",clock);
                var fail=new AtomicBoolean();
                var market=new MarketService(event->{if(fail.get())throw new IllegalStateException("rollback wake");},db,new UserFeatureService(db),protection);
                ReflectionTestUtils.setField(market,"clock",clock);ReflectionTestUtils.setField(market,"configuredSeed","12345");
                ReflectionTestUtils.setField(market,"batchEnabled",true);ReflectionTestUtils.setField(market,"batchParticipants",6020);
                ReflectionTestUtils.setField(market,"compactLedger",true);ReflectionTestUtils.setField(market,"inlineMarketMaker",true);
                ReflectionTestUtils.setField(market,"adaptiveActivity",true);
                market.initializeData();market.maintainScheduledMarket();
                var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(2);
                long human=db.queryForObject("SELECT id FROM users WHERE username='demo'",Long.class);
                long cash=totalCash(db),shares=db.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class);
                tx.executeWithoutResult(s->market.order(new MarketModels.OrderRequest("UMA","BUY",2,"LIMIT",7000L),human));
                long expired=db.queryForObject("SELECT MAX(id) FROM orders WHERE user_id=?",Long.class,human);
                db.update("UPDATE orders SET expires_at=? WHERE id=?",new Timestamp(start+30000),expired);
                var prices=db.queryForList("SELECT current_price FROM stocks ORDER BY id",Long.class);
                int idleBefore=source.acquisitions;
                for(long elapsed=500;elapsed<1800000;elapsed+=500) {
                    clock.now=start+elapsed;market.maintainScheduledMarket();
                    for(int i=0;i<4;i++)assertFalse(market.botWorkDue("batch:"+i));
                }
                assertEquals(idleBefore,source.acquisitions,"no JDBC connections/queries during quiet idle heartbeats");
                assertEquals(prices,db.queryForList("SELECT current_price FROM stocks ORDER BY id",Long.class));
                assertEquals(0,totalFills(db));

                clock.now=start+1800000;sqlClock(db,clock);market.maintainScheduledMarket();
                assertEquals(prices,db.queryForList("SELECT current_price FROM stocks ORDER BY id",Long.class),"environment refresh cannot move prices");
                assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,expired));
                assertEquals(cash,totalCash(db),"expiry refunds preserve cash");
                int attempts=0;
                for(int step=0;step<10;step++) {
                    clock.now=start+1800000+step*200;sqlClock(db,clock);
                    for(int i=0;i<4;i++)if(market.botWorkDue("batch:"+i)) {
                        int shard=i;tx.execute(s->market.participantBatch(shard));attempts++;
                    }
                }
                assertEquals(20,attempts,"five bounded attempts for each of four groups");
                long pulseFills=totalFills(db);assertTrue(pulseFills>0&&pulseFills<10000,"real matching, not millions of replayed fills");

                // Simulate persisted orders left behind by a visitor: one expires, one remains GTC.
                tx.executeWithoutResult(s->market.order(new MarketModels.OrderRequest("UMA","BUY",2,"LIMIT",7000L),human));
                long wakeExpired=db.queryForObject("SELECT MAX(id) FROM orders WHERE user_id=?",Long.class,human);
                long refund=db.queryForObject("SELECT reserved_cash FROM orders WHERE id=?",Long.class,wakeExpired);
                db.update("UPDATE orders SET expires_at=? WHERE id=?",new Timestamp(clock.now+1000),wakeExpired);
                tx.executeWithoutResult(s->market.order(new MarketModels.OrderRequest("UMA","BUY",1,"LIMIT",7000L),human));
                long gtc=db.queryForObject("SELECT MAX(id) FROM orders WHERE user_id=?",Long.class,human);
                long beforeWakeCash=db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,human);
                clock.now=start+2400000;sqlClock(db,clock);
                idleBefore=source.acquisitions;market.maintainScheduledMarket();
                for(int i=0;i<4;i++)assertFalse(market.botWorkDue("batch:"+i));
                assertEquals(idleBefore,source.acquisitions);

                fail.set(true);assertThrows(IllegalStateException.class,market::recordHumanActivity);
                assertEquals(beforeWakeCash,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,human));
                assertFalse(market.botWorkDue("batch:0"),"rollback must not open admission");
                fail.set(false);long began=System.nanoTime();market.recordHumanActivity();double wakeMs=(System.nanoTime()-began)/1e6;
                assertEquals(beforeWakeCash+refund,db.queryForObject("SELECT cash FROM users WHERE id=?",Long.class,human));
                assertEquals("CANCELLED",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,wakeExpired));
                assertEquals("OPEN",db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,gtc));
                assertEquals(pulseFills,totalFills(db),"wake does maintenance, not historical matching");
                int readyBefore=source.acquisitions;
                for(int i=0;i<20;i++)market.recordHumanActivity();
                assertEquals(readyBefore,source.acquisitions,"subsequent visitors do not repeat wake maintenance");
                assertEquals(100,market.participantCadenceMillis());assertEquals(300,market.currentTargetPerSymbol());
                int firstFills=0;
                for(int i=0;i<4;i++) {
                    int shard=i;assertTrue(market.botWorkDue("batch:"+i));
                    firstFills+=tx.execute(s->market.participantBatch(shard)).stream().mapToInt(BotActivityEngine.Activity::fills).sum();
                }
                assertTrue(firstFills<1000,"wake must not spend idle time as accumulated activity credits");
                long fees=db.queryForObject("SELECT COALESCE(SUM(fees),0) FROM bot_ledger_totals",Long.class)
                        +db.queryForObject("SELECT COALESCE(SUM(buyer_fee+seller_fee),0) FROM trades",Long.class);
                assertEquals(cash,totalCash(db)+fees);assertEquals(shares,db.queryForObject("SELECT SUM(quantity) FROM portfolios",Long.class));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users WHERE cash<0",Integer.class));
                var report=Map.of("simulatedIdleMinutes",40,"idleDatabaseAcquisitions",0,"pulseBatchAttempts",attempts,
                        "pulseFills",pulseFills,"successfulWakeMaintenanceMs",wakeMs,"firstActiveBatchFills",firstFills,"cashError",0,"shareError",0);
                Path out=Path.of("target/market-activity/idle-half-hour.json");Files.createDirectories(out.getParent());
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(),report);
                System.out.println("IDLE_PULSE "+report);
            } finally {db.execute("DROP DATABASE "+schema);}
        }
    }
    private static long totalCash(JdbcTemplate db) {
        return db.queryForObject("SELECT (SELECT SUM(cash) FROM users)+(SELECT COALESCE(SUM(reserved_cash),0) FROM orders WHERE status='OPEN')",Long.class);
    }
    private static long totalFills(JdbcTemplate db) {
        return db.queryForObject("SELECT COALESCE(SUM(fills),0) FROM bot_ledger_totals",Long.class)+db.queryForObject("SELECT COUNT(*) FROM trades",Long.class);
    }
}
