package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.zip.*;
import static com.gamestock.backend.market.BatchOrderBook.*;

/** Compact durable bot-only ledger. The journal, open orders and materialized account/position
 * checkpoint commit together. Human-involved executions keep the original SQL trade/settlement rows.
 * No asynchronous acknowledged writes and no synthetic trades are used.
 */
final class BotLedgerJournal {
    private static final int ORDER_CLEANUP_BATCH=5000;
    private static final int ORDER_CLEANUP_PASSES=4;
    private final JdbcTemplate db;
    BotLedgerJournal(JdbcTemplate db){this.db=db;}
    record Execution(long buyer,long seller,long buyOrder,long sellOrder,long makerOrder,long takerOrder,
                     int quantity,long price,long buyerFee,long sellerFee,String side,String type,
                     Holding buyerBefore,Holding sellerBefore) { }
    record Cash(long user,long before,long after) { }
    record Position(long user,Holding holding) { }
    record OrderState(long id,long user,String side,String type,long price,int quantity,int remaining,
                      long cash,int shares,String status,long created,long expires,boolean persisted) { }
    record Batch(long at,List<Execution> fills,List<Cash> accounts,List<Position> positions,List<OrderState> orders) { }
    record Print(long at,String side,int quantity,long price,String type) { }

    static void ensureTables(JdbcTemplate db) {
        db.execute("""
                CREATE TABLE IF NOT EXISTS bot_ledger_batches (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,stock_id BIGINT NOT NULL,created_at TIMESTAMP(3) NOT NULL,
                  fill_count INT NOT NULL,lp_fills INT NOT NULL,payload MEDIUMBLOB NOT NULL,
                  INDEX ix_bot_ledger_stock_time(stock_id,created_at,id),INDEX ix_bot_ledger_time(created_at,id))
                """);
        db.execute("""
                CREATE TABLE IF NOT EXISTS bot_ledger_totals (
                  stock_id BIGINT PRIMARY KEY,fills BIGINT NOT NULL,quantity BIGINT NOT NULL,fees BIGINT NOT NULL,
                  lp_cash_delta BIGINT NOT NULL,last_trade TIMESTAMP(3) NULL)
                """);
        db.execute("CREATE TABLE IF NOT EXISTS bot_account_fees(user_id BIGINT PRIMARY KEY,fees BIGINT NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS bot_trade_tape(stock_id BIGINT PRIMARY KEY,payload BLOB NOT NULL)");
        for(String table:List.of("bot_trade_seconds","bot_trade_minutes","bot_trade_hours"))db.execute("CREATE TABLE IF NOT EXISTS "+table+" ("
                +"stock_id BIGINT NOT NULL,bucket_at BIGINT NOT NULL,first_at BIGINT NOT NULL,last_at BIGINT NOT NULL,"
                +"open_price BIGINT NOT NULL,high_price BIGINT NOT NULL,low_price BIGINT NOT NULL,close_price BIGINT NOT NULL,"
                +"quantity BIGINT NOT NULL,notional DOUBLE NOT NULL,fills BIGINT NOT NULL,PRIMARY KEY(stock_id,bucket_at))");
        // Retention runs across symbols. Time indexes avoid rescanning days of retained candles.
        for(String table:List.of("bot_trade_seconds","bot_trade_minutes","bot_trade_hours")) {
            if(db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name='ix_retention_time'",Integer.class,table)==0)
                db.execute("CREATE INDEX ix_retention_time ON "+table+" (bucket_at)");
        }
        if(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='orders' AND column_name='compact_origin'",Integer.class)==0)
            db.execute("ALTER TABLE orders ADD COLUMN compact_origin BOOLEAN NOT NULL DEFAULT FALSE");
        if(db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='orders' AND index_name='ix_orders_compact_retention'",Integer.class)==0)
            db.execute("CREATE INDEX ix_orders_compact_retention ON orders(compact_origin,status,created_at,id)");
    }
    boolean compact(Fill fill,BatchOrderBook book) {
        return book.accounts.get(fill.buy().user).bot && book.accounts.get(fill.sell().user).bot;
    }
    void write(BatchOrderBook book,List<Fill> compact,long now) {
        Map<Long,List<Fill>> groups=new LinkedHashMap<>();
        compact.forEach(f->groups.computeIfAbsent(f.buy().stock,k->new ArrayList<>()).add(f));
        book.changedOrders.stream().filter(o->book.accounts.get(o.user).bot).forEach(o->groups.computeIfAbsent(o.stock,k->new ArrayList<>()));
        List<Object[]> batches=new ArrayList<>(),totals=new ArrayList<>(),seconds=new ArrayList<>(),minutes=new ArrayList<>(),hours=new ArrayList<>(),tapes=new ArrayList<>();
        Map<Long,Long> fees=new TreeMap<>();
        for(var group:groups.entrySet()) {
            long stock=group.getKey();var fills=group.getValue();
            Set<Long> owners=new HashSet<>();
            fills.forEach(f->{owners.add(f.buy().user);owners.add(f.sell().user);});
            var orders=book.changedOrders.stream().filter(o->o.stock==stock && book.accounts.get(o.user).bot).map(o->{owners.add(o.user);return new OrderState(o.id,o.user,o.side,o.type,o.price,o.quantity,o.remaining,o.reservedCash,o.reservedQuantity,o.status,o.createdAt,o.expiresAt,o.persisted);}).toList();
            var accounts=book.changedAccounts.stream().filter(owners::contains).map(id->{Account a=book.accounts.get(id);return new Cash(id,a.openingCash,a.cash);}).toList();
            var positions=book.changedHoldings.stream().filter(k->k.stock()==stock && book.accounts.get(k.user()).bot).map(k->new Position(k.user(),book.holdings.get(k))).toList();
            var executions=fills.stream().map(f->new Execution(f.buy().user,f.sell().user,f.buy().id,f.sell().id,f.maker().id,f.taker().id,f.quantity(),f.price(),f.buyerFee(),f.sellerFee(),f.taker().side,f.taker().type,f.buyerBefore(),f.sellerBefore())).toList();
            long lpFills=fills.stream().filter(f->book.accounts.get(f.buy().user).liquidityProvider||book.accounts.get(f.sell().user).liquidityProvider).count();
            batches.add(new Object[]{stock,new Timestamp(now),fills.size(),lpFills,encode(new Batch(now,executions,accounts,positions,orders))});
            if(fills.isEmpty())continue;
            List<Print> prints=new ArrayList<>();
            for(int i=fills.size()-1;i>=0&&prints.size()<10;i--){Fill f=fills.get(i);prints.add(new Print(now,f.taker().side,f.quantity(),f.price(),f.taker().type));}
            if(prints.size()<10)prints.addAll(book.lastPrints.computeIfAbsent(stock,this::recent));
            prints=List.copyOf(prints.subList(0,Math.min(10,prints.size())));book.lastPrints.put(stock,prints);
            tapes.add(new Object[]{stock,encodeTape(prints)});
            long quantity=0,totalFees=0,lpDelta=0,high=0,low=Long.MAX_VALUE;double notional=0;
            for(Fill f:fills) {
                quantity+=f.quantity();notional+=(double)f.quantity()*f.price();high=Math.max(high,f.price());low=Math.min(low,f.price());
                totalFees+=f.buyerFee()+f.sellerFee();fees.merge(f.buy().user,f.buyerFee(),Long::sum);fees.merge(f.sell().user,f.sellerFee(),Long::sum);
                if(book.accounts.get(f.buy().user).liquidityProvider)lpDelta-=f.quantity()*f.price()+f.buyerFee();
                if(book.accounts.get(f.sell().user).liquidityProvider)lpDelta+=f.quantity()*f.price()-f.sellerFee();
            }
            totals.add(new Object[]{stock,fills.size(),quantity,totalFees,lpDelta,new Timestamp(now)});
            Object[] row={stock,now/1000,now,now,fills.get(0).price(),high,low,fills.get(fills.size()-1).price(),quantity,notional,fills.size()};
            seconds.add(row);Object[] minute=row.clone();minute[1]=now/60000*60;minutes.add(minute);
            Object[] hour=row.clone();hour[1]=now/3600000*3600;hours.add(hour);
        }
        var writer=new BatchMarketRepository(db);
        writer.insert("bot_ledger_batches","stock_id,created_at,fill_count,lp_fills,payload",batches,false,"");
        writer.insert("bot_ledger_totals","stock_id,fills,quantity,fees,lp_cash_delta,last_trade",totals,false,
                " ON DUPLICATE KEY UPDATE fills=fills+VALUES(fills),quantity=quantity+VALUES(quantity),fees=fees+VALUES(fees),lp_cash_delta=lp_cash_delta+VALUES(lp_cash_delta),last_trade=VALUES(last_trade)");
        writer.insert("bot_account_fees","user_id,fees",fees.entrySet().stream().map(e->new Object[]{e.getKey(),e.getValue()}).toList(),false," ON DUPLICATE KEY UPDATE fees=fees+VALUES(fees)");
        writer.insert("bot_trade_tape","stock_id,payload",tapes,false," ON DUPLICATE KEY UPDATE payload=VALUES(payload)");
        writeBuckets(writer,"bot_trade_seconds",seconds);writeBuckets(writer,"bot_trade_minutes",minutes);writeBuckets(writer,"bot_trade_hours",hours);
    }
    private void writeBuckets(BatchMarketRepository writer,String table,List<Object[]> rows) {
        writer.insert(table,"stock_id,bucket_at,first_at,last_at,open_price,high_price,low_price,close_price,quantity,notional,fills",rows,false,
                " ON DUPLICATE KEY UPDATE open_price=IF(VALUES(first_at)<first_at,VALUES(open_price),open_price),"
                +"close_price=IF(VALUES(last_at)>=last_at,VALUES(close_price),close_price),first_at=LEAST(first_at,VALUES(first_at)),last_at=GREATEST(last_at,VALUES(last_at)),"
                +"high_price=GREATEST(high_price,VALUES(high_price)),low_price=LEAST(low_price,VALUES(low_price)),quantity=quantity+VALUES(quantity),notional=notional+VALUES(notional),fills=fills+VALUES(fills)");
    }
    List<PriceMetricService.Trade> observations(long stock,long cutoff) {
        return db.query("SELECT bucket_at,close_price,quantity,notional,high_price,low_price FROM bot_trade_seconds WHERE stock_id=? AND bucket_at>=? AND bucket_at<=? ORDER BY bucket_at",
                (rs,n)->new PriceMetricService.Trade(rs.getLong(1)*1000,rs.getDouble(2),rs.getLong(3),rs.getDouble(4),rs.getDouble(5),rs.getDouble(6)),stock,(cutoff-600000)/1000,cutoff/1000);
    }
    List<Print> recent(long stock) {
        return db.query("SELECT payload FROM bot_trade_tape WHERE stock_id=?",(rs,n)->decodeTape(rs.getBytes(1)),stock).stream().findFirst().orElse(List.of());
    }
    static byte[] encodeTape(List<Print> prints) {
        if(prints.size()>10)throw new IllegalArgumentException("Public tape is bounded to ten executions");
        try {
            var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
                out.writeInt(1);out.writeInt(prints.size());
                for(Print print:prints){out.writeLong(print.at());out.writeLong(print.price());out.writeInt(print.quantity());out.writeUTF(print.side());out.writeUTF(print.type());}
            }return bytes.toByteArray();
        }catch(IOException error){throw new IllegalStateException(error);}
    }
    static List<Print> decodeTape(byte[] payload) {
        try(var in=new DataInputStream(new ByteArrayInputStream(payload))) {
            if(in.readInt()!=1)throw new IOException("Unsupported tape version");
            int count=count(in);if(count>10)throw new IOException("Unbounded tape");
            var prints=new ArrayList<Print>();for(int i=0;i<count;i++){long at=in.readLong(),price=in.readLong();int quantity=in.readInt();prints.add(new Print(at,in.readUTF(),quantity,price,in.readUTF()));}
            if(in.read()!=-1)throw new IOException("Unexpected tape data");return List.copyOf(prints);
        }catch(IOException error){throw new IllegalStateException("Corrupt public tape",error);}
    }
    /** Bounded retention for new bot-only detail. Existing/user SQL ledgers are never purged here. */
    void retain(long now,int detailMinutes) {
        db.update("DELETE FROM bot_ledger_batches WHERE created_at<? ORDER BY created_at,id LIMIT 2000",new Timestamp(now-detailMinutes*60000L));
        db.update("DELETE FROM bot_trade_seconds WHERE bucket_at<? LIMIT 3000",now/1000-900);
        db.update("DELETE FROM bot_trade_minutes WHERE bucket_at<? LIMIT 3000",now/1000-8*86400);
        db.update("DELETE FROM bot_trade_hours WHERE bucket_at<? LIMIT 3000",now/1000-370*86400L);
        // These are replayable metric projections, not the trade ledger.
        db.update("DELETE FROM market_trade_buckets WHERE created_at<? LIMIT 3000",new Timestamp(now-900000));
        // Active books keep only OPEN bot orders in SQL. Completed/cancelled bot
        // rows are a short-lived recovery aid, never a permanent bot ledger.
        // Drain several bounded chunks so cleanup can keep up with active batches
        // without ever creating an unbounded DELETE or queue.
        for(int pass=0;pass<ORDER_CLEANUP_PASSES;pass++) {
            int deleted=deleteStaleOrders(new Timestamp(now-60000),ORDER_CLEANUP_BATCH);
            if(deleted<ORDER_CLEANUP_BATCH)break;
        }
    }
    private int deleteStaleOrders(Timestamp cutoff,int limit) {
        return db.update("""
                DELETE FROM orders
                WHERE id IN (
                  SELECT candidate.id FROM (
                    SELECT o.id FROM orders o
                    WHERE o.compact_origin=TRUE AND o.status<>'OPEN' AND o.created_at<?
                      AND NOT EXISTS(SELECT 1 FROM trades t WHERE t.buy_order_id=o.id)
                      AND NOT EXISTS(SELECT 1 FROM trades t WHERE t.sell_order_id=o.id)
                    ORDER BY o.id LIMIT ?
                  ) candidate
                )
                """,cutoff,limit);
    }
    void deleteOrders(List<Long> ids) {
        if(ids.isEmpty())return;
        // Only fresh compact-origin bot orders without a retained human/legacy trade reference.
        db.update("DELETE FROM orders WHERE compact_origin=TRUE AND status<>'OPEN' AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")"
                +" AND NOT EXISTS(SELECT 1 FROM trades t WHERE t.buy_order_id=orders.id) AND NOT EXISTS(SELECT 1 FROM trades t WHERE t.sell_order_id=orders.id)",ids.toArray());
    }
    static byte[] encode(Batch batch) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            // Prefer CPU efficiency over maximum compression for a continuous matching workload.
            try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(bytes) {{def.setLevel(Deflater.BEST_SPEED);}})) {
                out.writeInt(1);out.writeLong(batch.at());out.writeInt(batch.fills().size());
                for(Execution f:batch.fills()) {
                    out.writeLong(f.buyer());out.writeLong(f.seller());out.writeLong(f.buyOrder());out.writeLong(f.sellOrder());out.writeLong(f.makerOrder());out.writeLong(f.takerOrder());
                    out.writeInt(f.quantity());out.writeLong(f.price());out.writeLong(f.buyerFee());out.writeLong(f.sellerFee());out.writeUTF(f.side());out.writeUTF(f.type());holding(out,f.buyerBefore());holding(out,f.sellerBefore());
                }
                out.writeInt(batch.accounts().size());for(Cash c:batch.accounts()){out.writeLong(c.user());out.writeLong(c.before());out.writeLong(c.after());}
                out.writeInt(batch.positions().size());for(Position p:batch.positions()){out.writeLong(p.user());holding(out,p.holding());}
                out.writeInt(batch.orders().size());for(OrderState o:batch.orders()) {
                    out.writeLong(o.id());out.writeLong(o.user());out.writeUTF(o.side());out.writeUTF(o.type());out.writeLong(o.price());out.writeInt(o.quantity());out.writeInt(o.remaining());out.writeLong(o.cash());out.writeInt(o.shares());out.writeUTF(o.status());out.writeLong(o.created());out.writeLong(o.expires());out.writeBoolean(o.persisted());
                }
            }
            return bytes.toByteArray();
        } catch(IOException error){throw new IllegalStateException("Cannot encode bot ledger",error);}
    }
    static Batch decode(byte[] bytes) {
        try(DataInputStream in=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            if(in.readInt()!=1)throw new IOException("Unsupported bot ledger version");
            long at=in.readLong();List<Execution> fills=new ArrayList<>();
            for(int n=count(in);n>0;n--)fills.add(new Execution(in.readLong(),in.readLong(),in.readLong(),in.readLong(),in.readLong(),in.readLong(),in.readInt(),in.readLong(),in.readLong(),in.readLong(),in.readUTF(),in.readUTF(),holding(in),holding(in)));
            List<Cash> accounts=new ArrayList<>();for(int n=count(in);n>0;n--)accounts.add(new Cash(in.readLong(),in.readLong(),in.readLong()));
            List<Position> positions=new ArrayList<>();for(int n=count(in);n>0;n--)positions.add(new Position(in.readLong(),holding(in)));
            List<OrderState> orders=new ArrayList<>();for(int n=count(in);n>0;n--)orders.add(new OrderState(in.readLong(),in.readLong(),in.readUTF(),in.readUTF(),in.readLong(),in.readInt(),in.readInt(),in.readLong(),in.readInt(),in.readUTF(),in.readLong(),in.readLong(),in.readBoolean()));
            if(in.read()!=-1)throw new IOException("Unexpected bot ledger data");
            return new Batch(at,List.copyOf(fills),List.copyOf(accounts),List.copyOf(positions),List.copyOf(orders));
        } catch(IOException error){throw new IllegalStateException("Corrupt bot ledger",error);}
    }
    private static int count(DataInputStream in)throws IOException {int n=in.readInt();if(n<0||n>100000)throw new IOException("Invalid bot ledger count");return n;}
    private static void holding(DataOutputStream out,Holding h)throws IOException {out.writeInt(h.quantity());out.writeInt(h.settled());out.writeLong(h.average());out.writeLong(h.realized());}
    private static Holding holding(DataInputStream in)throws IOException{return new Holding(in.readInt(),in.readInt(),in.readLong(),in.readLong());}
}
