package com.gamestock.backend.market;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;

import static com.gamestock.backend.market.BatchOrderBook.*;

/**
 * Storage projection for the in-memory matching book.
 *
 * <p>The name is kept for source compatibility with the previous compact-ledger
 * implementation. It no longer writes a per-batch bot payload. Bot orders are
 * kept in {@code orders} only while they are open or while a human-facing
 * trade still needs their foreign key. Closed bot-only activity is reduced to
 * {@code bot_stats}, {@code market_candles}, and small cumulative counters.</p>
 */
final class BotLedgerJournal {
    private static final int SECOND = 1;
    private static final int MINUTE = 60;
    private static final long SECOND_RETENTION_SECONDS = 24L * 60 * 60;
    private static final long MINUTE_RETENTION_SECONDS = 365L * 24 * 60 * 60;
    private static final long SNAPSHOT_RETENTION_SECONDS = 365L * 24 * 60 * 60;
    private static final long RETENTION_INTERVAL_MILLIS = 30_000L;

    private final JdbcTemplate db;
    private final AtomicLong lastRetentionAt = new AtomicLong();

    /*
     * These records remain as a compatibility decoder for old test fixtures and
     * offline diagnostics. write() never serializes them into MySQL anymore.
     */
    record Execution(long buyer,long seller,long buyOrder,long sellOrder,long makerOrder,long takerOrder,
                     int quantity,long price,long buyerFee,long sellerFee,String side,String type,
                     Holding buyerBefore,Holding sellerBefore) { }
    record Cash(long user,long before,long after) { }
    record Position(long user,Holding holding) { }
    record OrderState(long id,long user,String side,String type,long price,int quantity,int remaining,
                      long cash,int shares,String status,long created,long expires,boolean persisted) { }
    record Batch(long at,List<Execution> fills,List<Cash> accounts,List<Position> positions,List<OrderState> orders) { }
    record Print(long at,String side,int quantity,long price,String type) { }

    private record Bucket(long stock,int intervalSeconds,long bucketAt) { }

    private static final class Candle {
        long firstAt=Long.MAX_VALUE;
        long lastAt=Long.MIN_VALUE;
        long open;
        long high=Long.MIN_VALUE;
        long low=Long.MAX_VALUE;
        long close;
        long volume;
        long notional;
        long trades;

        void trade(long at,long price,long quantity) {
            if(at<firstAt){firstAt=at;open=price;}
            if(at>=lastAt){lastAt=at;close=price;}
            high=Math.max(high,price);
            low=Math.min(low,price);
            volume+=quantity;
            notional+=price*quantity;
            trades++;
            if(lastAt==Long.MIN_VALUE)lastAt=at;
            else lastAt=Math.max(lastAt,at);
        }
    }

    private static final class BotStat {
        long firstAt=Long.MAX_VALUE;
        long lastAt=Long.MIN_VALUE;
        long firstOrderAt=Long.MAX_VALUE;
        long lastOrderAt=Long.MIN_VALUE;
        long orderCount;
        long tradeCount;
        long buyVolume;
        long sellVolume;
        long open;
        long high=Long.MIN_VALUE;
        long low=Long.MAX_VALUE;
        long close;
        long volume;
        long notional;

        void order(long at) {
            firstOrderAt=Math.min(firstOrderAt,at);
            lastOrderAt=Math.max(lastOrderAt,at);
            orderCount++;
        }

        void trade(long at,long price,long quantity,boolean buyerBot,boolean sellerBot) {
            if(at<firstAt){firstAt=at;open=price;}
            if(at>=lastAt){lastAt=at;close=price;}
            high=Math.max(high,price);
            low=Math.min(low,price);
            tradeCount++;
            if(buyerBot)buyVolume+=quantity;
            if(sellerBot)sellVolume+=quantity;
            volume+=quantity;
            notional+=price*quantity;
        }

        long effectiveFirstAt(){return firstAt==Long.MAX_VALUE?firstOrderAt:firstAt;}
        long effectiveLastAt(){return lastAt==Long.MIN_VALUE?lastOrderAt:lastAt;}

        Long nullableOpen(){return tradeCount==0?null:open;}
        Long nullableHigh(){return tradeCount==0?null:high;}
        Long nullableLow(){return tradeCount==0?null:low;}
        Long nullableClose(){return tradeCount==0?null:close;}
    }

    BotLedgerJournal(JdbcTemplate db){this.db=db;}

    /**
     * Creates only compact projections. The old bot_ledger_batches table is
     * deliberately not created here: bot raw order/fill payloads are not a
     * durable storage format anymore.
     */
    static void ensureTables(JdbcTemplate db) {
        db.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                  user_id BIGINT PRIMARY KEY,
                  cash BIGINT NOT NULL,
                  total_asset BIGINT NOT NULL,
                  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
                  CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        db.execute("""
                CREATE TABLE IF NOT EXISTS positions (
                  user_id BIGINT NOT NULL,
                  stock_id BIGINT NOT NULL,
                  quantity INT NOT NULL DEFAULT 0,
                  avg_price BIGINT NOT NULL DEFAULT 0,
                  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (user_id,stock_id),
                  CONSTRAINT fk_positions_user FOREIGN KEY (user_id) REFERENCES users(id),
                  CONSTRAINT fk_positions_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
                )
                """);
        db.execute("""
                CREATE TABLE IF NOT EXISTS market_candles (
                  stock_id BIGINT NOT NULL,
                  interval_seconds SMALLINT UNSIGNED NOT NULL,
                  bucket_at BIGINT NOT NULL,
                  first_at BIGINT NOT NULL,
                  last_at BIGINT NOT NULL,
                  open_price BIGINT NOT NULL,
                  high_price BIGINT NOT NULL,
                  low_price BIGINT NOT NULL,
                  close_price BIGINT NOT NULL,
                  volume BIGINT NOT NULL DEFAULT 0,
                  notional BIGINT NOT NULL DEFAULT 0,
                  trade_count BIGINT NOT NULL DEFAULT 0,
                  PRIMARY KEY (stock_id,interval_seconds,bucket_at),
                  CONSTRAINT fk_market_candles_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_market_candles_retention (interval_seconds,bucket_at)
                )
                """);
        db.execute("""
                CREATE TABLE IF NOT EXISTS bot_stats (
                  stock_id BIGINT NOT NULL,
                  interval_seconds SMALLINT UNSIGNED NOT NULL,
                  bucket_at BIGINT NOT NULL,
                  first_at BIGINT NOT NULL,
                  last_at BIGINT NOT NULL,
                  order_count BIGINT NOT NULL DEFAULT 0,
                  trade_count BIGINT NOT NULL DEFAULT 0,
                  buy_volume BIGINT NOT NULL DEFAULT 0,
                  sell_volume BIGINT NOT NULL DEFAULT 0,
                  open_price BIGINT NULL,
                  high_price BIGINT NULL,
                  low_price BIGINT NULL,
                  close_price BIGINT NULL,
                  volume BIGINT NOT NULL DEFAULT 0,
                  notional BIGINT NOT NULL DEFAULT 0,
                  PRIMARY KEY (stock_id,interval_seconds,bucket_at),
                  CONSTRAINT fk_bot_stats_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_bot_stats_retention (interval_seconds,bucket_at)
                )
                """);
        db.execute("""
                CREATE TABLE IF NOT EXISTS account_snapshots (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  captured_at TIMESTAMP NOT NULL,
                  cash BIGINT NOT NULL,
                  total_asset BIGINT NOT NULL,
                  pnl BIGINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uq_account_snapshot_user_time (user_id,captured_at),
                  CONSTRAINT fk_account_snapshots_user FOREIGN KEY (user_id) REFERENCES users(id),
                  INDEX ix_account_snapshots_retention (captured_at)
                )
                """);
        db.execute("""
                CREATE TABLE IF NOT EXISTS bot_ledger_totals (
                  stock_id BIGINT PRIMARY KEY,
                  fills BIGINT NOT NULL,
                  quantity BIGINT NOT NULL,
                  fees BIGINT NOT NULL,
                  lp_cash_delta BIGINT NOT NULL,
                  last_trade TIMESTAMP(3) NULL,
                  CONSTRAINT fk_bot_totals_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
                )
                """);
        db.execute("CREATE TABLE IF NOT EXISTS bot_account_fees(user_id BIGINT PRIMARY KEY,fees BIGINT NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS bot_trade_tape(stock_id BIGINT PRIMARY KEY,payload BLOB NOT NULL)");

        if(columnMissing(db,"orders","compact_origin"))
            db.execute("ALTER TABLE orders ADD COLUMN compact_origin BOOLEAN NOT NULL DEFAULT FALSE");
        ensureIndex(db,"orders","ix_orders_compact_retention","compact_origin,status,created_at,id");

        // Do not copy legacy candles or all users/portfolios while Spring is
        // starting. Existing Railway volumes can be large enough for either
        // full INSERT ... SELECT to hold the startup transaction past the
        // health-check window. New writes project changed state incrementally;
        // legacy projections remain cleanup-only compatibility data.
    }

    private static boolean columnMissing(JdbcTemplate db,String table,String column) {
        Integer count=db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """,Integer.class,table,column);
        return count!=null&&count==0;
    }

    private static void ensureIndex(JdbcTemplate db,String table,String name,String columns) {
        Integer count=db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                """,Integer.class,table,name);
        if(count==null||count==0)db.execute("CREATE INDEX "+name+" ON "+table+" ("+columns+")");
    }

    private static boolean tableExists(JdbcTemplate db,String table) {
        Integer count=db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """,Integer.class,table);
        return count!=null&&count>0;
    }

    private static void migrateLegacyCandleTable(JdbcTemplate db,String table,int interval) {
        if(!tableExists(db,table))return;
        try {
            db.update("""
                    INSERT IGNORE INTO market_candles
                      (stock_id,interval_seconds,bucket_at,first_at,last_at,open_price,high_price,low_price,close_price,volume,notional,trade_count)
                    SELECT stock_id,?,bucket_at,first_at,last_at,open_price,high_price,low_price,close_price,quantity,
                           ROUND(notional),fills
                    FROM """+table,interval);
        } catch(DataAccessException ignored) {
            // A partially migrated legacy table must not prevent the market
            // from starting; the next maintenance run can retry it.
        }
    }

    boolean compact(Fill fill,BatchOrderBook book) {
        return bot(book,fill.buy().user)&&bot(book,fill.sell().user);
    }

    private boolean bot(BatchOrderBook book,long user) {
        Account account=book.accounts.get(user);
        return account!=null&&account.bot;
    }

    private static long bucket(long at,int intervalSeconds) {
        return Math.floorDiv(at,intervalSeconds*1000L)*intervalSeconds;
    }

    /**
     * Persists only bucket deltas. A single matching transaction may update
     * the same second/minute row several times, but it never creates a new
     * row per bot order or per bot-only fill.
     */
    void write(BatchOrderBook book,List<Fill> compact,long now) {
        Map<Bucket,Candle> candles=new LinkedHashMap<>();
        Map<Bucket,BotStat> stats=new LinkedHashMap<>();

        for(Fill fill:book.fills) {
            long at=now;
            for(int interval:List.of(SECOND,MINUTE)) {
                candles.computeIfAbsent(new Bucket(fill.buy().stock,interval,bucket(at,interval)),key->new Candle())
                        .trade(at,fill.price(),fill.quantity());
            }
            boolean buyerBot=bot(book,fill.buy().user),sellerBot=bot(book,fill.sell().user);
            if(buyerBot||sellerBot)for(int interval:List.of(SECOND,MINUTE)) {
                stats.computeIfAbsent(new Bucket(fill.buy().stock,interval,bucket(at,interval)),key->new BotStat())
                        .trade(at,fill.price(),fill.quantity(),buyerBot,sellerBot);
            }
        }
        for(Order order:book.accepted)if(bot(book,order.user)) {
            long at=order.createdAt>0?order.createdAt:now;
            for(int interval:List.of(SECOND,MINUTE))
                stats.computeIfAbsent(new Bucket(order.stock,interval,bucket(at,interval)),key->new BotStat()).order(at);
        }

        BatchMarketRepository writer=new BatchMarketRepository(db);
        List<Object[]> candleRows=candles.entrySet().stream().map(entry->{
            Bucket key=entry.getKey();Candle value=entry.getValue();
            return new Object[]{key.stock,key.intervalSeconds,key.bucketAt,value.firstAt,value.lastAt,value.open,value.high,
                    value.low,value.close,value.volume,value.notional,value.trades};
        }).toList();
        writer.insert("market_candles",
                "stock_id,interval_seconds,bucket_at,first_at,last_at,open_price,high_price,low_price,close_price,volume,notional,trade_count",
                candleRows,false," ON DUPLICATE KEY UPDATE "
                        +"open_price=IF(VALUES(first_at)<first_at,VALUES(open_price),open_price),"
                        +"close_price=IF(VALUES(last_at)>=last_at,VALUES(close_price),close_price),"
                        +"first_at=LEAST(first_at,VALUES(first_at)),last_at=GREATEST(last_at,VALUES(last_at)),"
                        +"high_price=GREATEST(high_price,VALUES(high_price)),low_price=LEAST(low_price,VALUES(low_price)),"
                        +"volume=volume+VALUES(volume),notional=notional+VALUES(notional),trade_count=trade_count+VALUES(trade_count)");

        List<Object[]> statRows=stats.entrySet().stream().map(entry->{
            Bucket key=entry.getKey();BotStat value=entry.getValue();
            return new Object[]{key.stock,key.intervalSeconds,key.bucketAt,value.effectiveFirstAt(),value.effectiveLastAt(),value.orderCount,value.tradeCount,
                    value.buyVolume,value.sellVolume,value.nullableOpen(),value.nullableHigh(),value.nullableLow(),value.nullableClose(),
                    value.volume,value.notional};
        }).toList();
        writer.insert("bot_stats",
                "stock_id,interval_seconds,bucket_at,first_at,last_at,order_count,trade_count,buy_volume,sell_volume,open_price,high_price,low_price,close_price,volume,notional",
                statRows,false," ON DUPLICATE KEY UPDATE "
                        +"order_count=order_count+VALUES(order_count),trade_count=trade_count+VALUES(trade_count),"
                        +"buy_volume=buy_volume+VALUES(buy_volume),sell_volume=sell_volume+VALUES(sell_volume),"
                        +"first_at=CASE WHEN VALUES(open_price) IS NULL THEN first_at WHEN open_price IS NULL THEN VALUES(first_at) ELSE LEAST(first_at,VALUES(first_at)) END,"
                        +"last_at=CASE WHEN VALUES(close_price) IS NULL THEN last_at WHEN close_price IS NULL THEN VALUES(last_at) ELSE GREATEST(last_at,VALUES(last_at)) END,"
                        +"open_price=CASE WHEN VALUES(open_price) IS NULL THEN open_price WHEN open_price IS NULL OR VALUES(first_at)<first_at THEN VALUES(open_price) ELSE open_price END,"
                        +"close_price=CASE WHEN VALUES(close_price) IS NULL THEN close_price WHEN close_price IS NULL OR VALUES(last_at)>=last_at THEN VALUES(close_price) ELSE close_price END,"
                        +"high_price=CASE WHEN VALUES(high_price) IS NULL THEN high_price ELSE IFNULL(GREATEST(high_price,VALUES(high_price)),VALUES(high_price)) END,"
                        +"low_price=CASE WHEN VALUES(low_price) IS NULL THEN low_price ELSE IFNULL(LEAST(low_price,VALUES(low_price)),VALUES(low_price)) END,"
                        +"volume=volume+VALUES(volume),notional=notional+VALUES(notional)");

        writeCompactTotals(writer,book,compact,now);
        syncCurrentState(book);
    }

    private void writeCompactTotals(BatchMarketRepository writer,BatchOrderBook book,List<Fill> compact,long now) {
        if(compact.isEmpty())return;
        Map<Long,List<Fill>> groups=new LinkedHashMap<>();
        compact.forEach(fill->groups.computeIfAbsent(fill.buy().stock,key->new ArrayList<>()).add(fill));
        List<Object[]> totals=new ArrayList<>(),tapes=new ArrayList<>();
        Map<Long,Long> fees=new TreeMap<>();
        for(var entry:groups.entrySet()) {
            long stock=entry.getKey();List<Fill> fills=entry.getValue();
            List<Print> prints=new ArrayList<>();
            for(int i=fills.size()-1;i>=0&&prints.size()<10;i--) {
                Fill fill=fills.get(i);
                prints.add(new Print(now,fill.taker().side,fill.quantity(),fill.price(),fill.taker().type));
            }
            if(prints.size()<10)prints.addAll(recent(stock));
            prints=List.copyOf(prints.subList(0,Math.min(10,prints.size())));
            tapes.add(new Object[]{stock,encodeTape(prints)});

            long quantity=0,totalFees=0,lpDelta=0;
            for(Fill fill:fills) {
                quantity+=fill.quantity();
                totalFees+=fill.buyerFee()+fill.sellerFee();
                fees.merge(fill.buy().user,fill.buyerFee(),Long::sum);
                fees.merge(fill.sell().user,fill.sellerFee(),Long::sum);
                if(book.accounts.get(fill.buy().user).liquidityProvider)
                    lpDelta-=fill.quantity()*fill.price()+fill.buyerFee();
                if(book.accounts.get(fill.sell().user).liquidityProvider)
                    lpDelta+=fill.quantity()*fill.price()-fill.sellerFee();
            }
            totals.add(new Object[]{stock,fills.size(),quantity,totalFees,lpDelta,new Timestamp(now)});
        }
        writer.insert("bot_ledger_totals","stock_id,fills,quantity,fees,lp_cash_delta,last_trade",totals,false,
                " ON DUPLICATE KEY UPDATE fills=fills+VALUES(fills),quantity=quantity+VALUES(quantity),"
                        +"fees=fees+VALUES(fees),lp_cash_delta=lp_cash_delta+VALUES(lp_cash_delta),last_trade=VALUES(last_trade)");
        writer.insert("bot_account_fees","user_id,fees",fees.entrySet().stream().map(e->new Object[]{e.getKey(),e.getValue()}).toList(),
                false," ON DUPLICATE KEY UPDATE fees=fees+VALUES(fees)");
        writer.insert("bot_trade_tape","stock_id,payload",tapes,false," ON DUPLICATE KEY UPDATE payload=VALUES(payload)");
    }

    private void syncCurrentState(BatchOrderBook book) {
        Set<Long> accounts=new TreeSet<>(book.changedAccounts);
        if(!accounts.isEmpty()) {
            String ids=inList(accounts);
            db.update("INSERT INTO accounts(user_id,cash,total_asset,updated_at) "
                    +"SELECT u.id,u.cash,u.cash+COALESCE(SUM(p.quantity*s.current_price),0),CURRENT_TIMESTAMP(3) "
                    +"FROM users u LEFT JOIN portfolios p ON p.user_id=u.id LEFT JOIN stocks s ON s.id=p.stock_id "
                    +"WHERE u.id IN ("+ids+") GROUP BY u.id,u.cash "
                    +"ON DUPLICATE KEY UPDATE cash=VALUES(cash),total_asset=VALUES(total_asset),updated_at=VALUES(updated_at)");
        }
        if(book.changedHoldings.isEmpty())return;
        List<PositionKey> positions=book.changedHoldings.stream().sorted(Comparator.comparingLong(PositionKey::user).thenComparingLong(PositionKey::stock)).toList();
        List<Object[]> values=positions.stream().map(key->new Object[]{key.user(),key.stock()}).toList();
        db.batchUpdate("DELETE FROM positions WHERE user_id=? AND stock_id=?",values);
        db.batchUpdate("""
                INSERT INTO positions(user_id,stock_id,quantity,avg_price,updated_at)
                SELECT p.user_id,p.stock_id,p.quantity,p.average_price,CURRENT_TIMESTAMP(3)
                FROM portfolios p WHERE p.user_id=? AND p.stock_id=? AND p.quantity<>0
                ON DUPLICATE KEY UPDATE quantity=VALUES(quantity),avg_price=VALUES(avg_price),updated_at=VALUES(updated_at)
                """,values);
    }

    private static String inList(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    void snapshot(long now) {
        long bucket=now/3_600_000L*3_600_000L;
        Timestamp captured=new Timestamp(bucket);
        db.update("""
                INSERT INTO account_snapshots(user_id,captured_at,cash,total_asset,pnl)
                SELECT u.id,?,u.cash,
                       u.cash+COALESCE(SUM(p.quantity*s.current_price),0),
                       u.cash+COALESCE(SUM(p.quantity*s.current_price),0)-1000000
                FROM users u
                LEFT JOIN portfolios p ON p.user_id=u.id AND p.quantity<>0
                LEFT JOIN stocks s ON s.id=p.stock_id
                WHERE u.password_hash NOT IN ('BOT','TRADER')
                GROUP BY u.id,u.cash
                ON DUPLICATE KEY UPDATE cash=VALUES(cash),total_asset=VALUES(total_asset),pnl=VALUES(pnl)
                """,captured);
    }

    /**
     * Legacy non-batch price movement still enters the same candle projection;
     * it does not recreate the old per-tick stock_price_history stream.
     */
    void recordLegacyTrade(long stock,long at,long price,long volume,boolean bot) {
        BatchMarketRepository writer=new BatchMarketRepository(db);
        for(int interval:List.of(SECOND,MINUTE)) {
            long bucket=bucket(at,interval);
            Object[] row={stock,interval,bucket,at,at,price,price,price,price,volume,price*volume,1};
            writer.insert("market_candles",
                    "stock_id,interval_seconds,bucket_at,first_at,last_at,open_price,high_price,low_price,close_price,volume,notional,trade_count",
                    Collections.singletonList(row),false," ON DUPLICATE KEY UPDATE "
                            +"close_price=VALUES(close_price),last_at=GREATEST(last_at,VALUES(last_at)),"
                            +"high_price=GREATEST(high_price,VALUES(high_price)),low_price=LEAST(low_price,VALUES(low_price)),"
                            +"volume=volume+VALUES(volume),notional=notional+VALUES(notional),trade_count=trade_count+1");
            if(bot) {
                Object[] stats={stock,interval,bucket,at,at,0,1,volume,volume,price,price,price,price,volume,price*volume};
                writer.insert("bot_stats",
                        "stock_id,interval_seconds,bucket_at,first_at,last_at,order_count,trade_count,buy_volume,sell_volume,open_price,high_price,low_price,close_price,volume,notional",
                        Collections.singletonList(stats),false," ON DUPLICATE KEY UPDATE trade_count=trade_count+1,buy_volume=buy_volume+VALUES(buy_volume),"
                                +"sell_volume=sell_volume+VALUES(sell_volume),close_price=VALUES(close_price),last_at=GREATEST(last_at,VALUES(last_at)),"
                                +"high_price=GREATEST(high_price,VALUES(high_price)),low_price=LEAST(low_price,VALUES(low_price)),"
                                +"volume=volume+VALUES(volume),notional=notional+VALUES(notional)");
            }
        }
    }

    List<PriceMetricService.Trade> observations(long stock,long cutoff) {
        return db.query("""
                SELECT bucket_at,close_price,volume,notional,high_price,low_price
                FROM market_candles
                WHERE stock_id=? AND interval_seconds=1 AND bucket_at>=? AND bucket_at<=?
                ORDER BY bucket_at
                """,
                (rs,n)->new PriceMetricService.Trade(rs.getLong(1)*1000,rs.getDouble(2),rs.getLong(3),
                        rs.getDouble(4),rs.getDouble(5),rs.getDouble(6)),
                stock,(cutoff-600000)/1000,cutoff/1000);
    }

    List<Print> recent(long stock) {
        return db.query("SELECT payload FROM bot_trade_tape WHERE stock_id=?",
                (rs,n)->decodeTape(rs.getBytes(1)),stock).stream().findFirst().orElse(List.of());
    }

    /**
     * Retention is intentionally throttled: maintenance can run every 500 ms,
     * while cleanup needs one short indexed pass about every 30 seconds.
     */
    void retain(long now,int ignoredDetailMinutes) {
        long previous=lastRetentionAt.get();
        if(previous!=0&&now-previous<RETENTION_INTERVAL_MILLIS)return;
        if(!lastRetentionAt.compareAndSet(previous,now))return;
        long seconds=now/1000L;
        db.update("DELETE FROM market_candles WHERE interval_seconds=1 AND bucket_at<? LIMIT 5000",seconds-SECOND_RETENTION_SECONDS);
        db.update("DELETE FROM bot_stats WHERE interval_seconds=1 AND bucket_at<? LIMIT 5000",seconds-SECOND_RETENTION_SECONDS);
        db.update("DELETE FROM market_candles WHERE interval_seconds=60 AND bucket_at<? LIMIT 5000",seconds-MINUTE_RETENTION_SECONDS);
        db.update("DELETE FROM market_candles WHERE interval_seconds=3600 AND bucket_at<? LIMIT 2000",seconds-MINUTE_RETENTION_SECONDS);
        db.update("DELETE FROM bot_stats WHERE interval_seconds=60 AND bucket_at<? LIMIT 5000",seconds-MINUTE_RETENTION_SECONDS);
        db.update("DELETE FROM account_snapshots WHERE captured_at<? LIMIT 2000",
                new Timestamp(now-SNAPSHOT_RETENTION_SECONDS*1000L));
        // Legacy projections are not part of the new write path. Clean them
        // incrementally when they exist on an older Railway volume.
        deleteIfTable(db,"bot_trade_seconds","bucket_at<? LIMIT 3000",seconds-SECOND_RETENTION_SECONDS);
        deleteIfTable(db,"bot_trade_minutes","bucket_at<? LIMIT 3000",seconds-8*86400L);
        deleteIfTable(db,"bot_trade_hours","bucket_at<? LIMIT 3000",seconds-370*86400L);
        deleteIfTable(db,"market_trade_buckets","created_at<? LIMIT 3000",new Timestamp(now-900000));
        deleteIfTable(db,"bot_ledger_batches","created_at<? LIMIT 2000",new Timestamp(now-Math.max(15,ignoredDetailMinutes)*60000L));
        // The table is retained as a compatibility source for old news rows,
        // but new matching never inserts per-fill points into it.
        deleteIfTable(db,"stock_price_history","recorded_at<? LIMIT 3000",new Timestamp(now-86400000L));

        List<Long> closed=db.queryForList("""
                SELECT o.id FROM orders o
                WHERE o.compact_origin=TRUE AND o.status<>'OPEN' AND o.created_at<?
                  AND NOT EXISTS(SELECT 1 FROM trades t WHERE t.buy_order_id=o.id)
                  AND NOT EXISTS(SELECT 1 FROM trades t WHERE t.sell_order_id=o.id)
                ORDER BY o.id LIMIT 1000
                """,Long.class,new Timestamp(now-60000L));
        deleteOrders(closed);
    }

    private static void deleteIfTable(JdbcTemplate db,String table,String predicate,Object argument) {
        if(tableExists(db,table)) {
            try{db.update("DELETE FROM "+table+" WHERE "+predicate,argument);}
            catch(DataAccessException ignored){}
        }
    }

    void deleteOrders(List<Long> ids) {
        if(ids.isEmpty())return;
        db.update("DELETE FROM orders WHERE compact_origin=TRUE AND status<>'OPEN' AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")"
                +" AND NOT EXISTS(SELECT 1 FROM trades t WHERE t.buy_order_id=orders.id)"
                +" AND NOT EXISTS(SELECT 1 FROM trades t WHERE t.sell_order_id=orders.id)",ids.toArray());
    }

    static byte[] encodeTape(List<Print> prints) {
        if(prints.size()>10)throw new IllegalArgumentException("Public tape is bounded to ten executions");
        try {
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(bytes)) {
                out.writeInt(1);out.writeInt(prints.size());
                for(Print print:prints){
                    out.writeLong(print.at());out.writeLong(print.price());out.writeInt(print.quantity());
                    out.writeUTF(print.side());out.writeUTF(print.type());
                }
            }
            return bytes.toByteArray();
        }catch(IOException error){throw new IllegalStateException(error);}
    }

    static List<Print> decodeTape(byte[] payload) {
        try(var in=new DataInputStream(new ByteArrayInputStream(payload))) {
            if(in.readInt()!=1)throw new IOException("Unsupported tape version");
            int count=count(in);if(count>10)throw new IOException("Unbounded tape");
            var prints=new ArrayList<Print>();
            for(int i=0;i<count;i++){
                long at=in.readLong(),price=in.readLong();int quantity=in.readInt();
                prints.add(new Print(at,in.readUTF(),quantity,price,in.readUTF()));
            }
            if(in.read()!=-1)throw new IOException("Unexpected tape data");
            return List.copyOf(prints);
        }catch(IOException error){throw new IllegalStateException("Corrupt public tape",error);}
    }

    /*
     * Offline compatibility codec. It is intentionally not called by the
     * database writer; keeping it lets old diagnostic fixtures be decoded
     * while the production schema stops creating bot_ledger_batches.
     */
    static byte[] encode(Batch batch) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(bytes) {{
                def.setLevel(Deflater.BEST_SPEED);
            }})) {
                out.writeInt(1);out.writeLong(batch.at());out.writeInt(batch.fills().size());
                for(Execution f:batch.fills()) {
                    out.writeLong(f.buyer());out.writeLong(f.seller());out.writeLong(f.buyOrder());out.writeLong(f.sellOrder());
                    out.writeLong(f.makerOrder());out.writeLong(f.takerOrder());out.writeInt(f.quantity());out.writeLong(f.price());
                    out.writeLong(f.buyerFee());out.writeLong(f.sellerFee());out.writeUTF(f.side());out.writeUTF(f.type());
                    holding(out,f.buyerBefore());holding(out,f.sellerBefore());
                }
                out.writeInt(batch.accounts().size());
                for(Cash c:batch.accounts()){out.writeLong(c.user());out.writeLong(c.before());out.writeLong(c.after());}
                out.writeInt(batch.positions().size());
                for(Position p:batch.positions()){out.writeLong(p.user());holding(out,p.holding());}
                out.writeInt(batch.orders().size());
                for(OrderState o:batch.orders()) {
                    out.writeLong(o.id());out.writeLong(o.user());out.writeUTF(o.side());out.writeUTF(o.type());out.writeLong(o.price());
                    out.writeInt(o.quantity());out.writeInt(o.remaining());out.writeLong(o.cash());out.writeInt(o.shares());
                    out.writeUTF(o.status());out.writeLong(o.created());out.writeLong(o.expires());out.writeBoolean(o.persisted());
                }
            }
            return bytes.toByteArray();
        } catch(IOException error){throw new IllegalStateException("Cannot serialize diagnostic batch",error);}
    }

    static Batch decode(byte[] bytes) {
        try(DataInputStream in=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            if(in.readInt()!=1)throw new IOException("Unsupported bot ledger version");
            long at=in.readLong();List<Execution> fills=new ArrayList<>();
            for(int n=count(in);n>0;n--)fills.add(new Execution(in.readLong(),in.readLong(),in.readLong(),in.readLong(),
                    in.readLong(),in.readLong(),in.readInt(),in.readLong(),in.readLong(),in.readLong(),in.readUTF(),in.readUTF(),
                    holding(in),holding(in)));
            List<Cash> accounts=new ArrayList<>();
            for(int n=count(in);n>0;n--)accounts.add(new Cash(in.readLong(),in.readLong(),in.readLong()));
            List<Position> positions=new ArrayList<>();
            for(int n=count(in);n>0;n--)positions.add(new Position(in.readLong(),holding(in)));
            List<OrderState> orders=new ArrayList<>();
            for(int n=count(in);n>0;n--)orders.add(new OrderState(in.readLong(),in.readLong(),in.readUTF(),in.readUTF(),in.readLong(),
                    in.readInt(),in.readInt(),in.readLong(),in.readInt(),in.readUTF(),in.readLong(),in.readLong(),in.readBoolean()));
            if(in.read()!=-1)throw new IOException("Unexpected bot ledger data");
            return new Batch(at,List.copyOf(fills),List.copyOf(accounts),List.copyOf(positions),List.copyOf(orders));
        } catch(IOException error){throw new IllegalStateException("Corrupt bot ledger",error);}
    }

    private static int count(DataInputStream in)throws IOException {
        int n=in.readInt();if(n<0||n>100000)throw new IOException("Invalid bot ledger count");return n;
    }
    private static void holding(DataOutputStream out,Holding h)throws IOException {
        out.writeInt(h.quantity());out.writeInt(h.settled());out.writeLong(h.average());out.writeLong(h.realized());
    }
    private static Holding holding(DataInputStream in)throws IOException {
        return new Holding(in.readInt(),in.readInt(),in.readLong(),in.readLong());
    }
}
