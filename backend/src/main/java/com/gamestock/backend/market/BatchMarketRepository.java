package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import static com.gamestock.backend.market.BatchOrderBook.*;

/** JDBC adapter for internal bot batches. Caller owns one READ_COMMITTED transaction. */
final class BatchMarketRepository {
    private final JdbcTemplate db;
    private final BotLedgerJournal journal;
    final Map<String,Long> timings=new LinkedHashMap<>();
    private long measuredAt;
    private void stamp(String key) {long end=System.nanoTime();timings.put("persist."+key,end-measuredAt);measuredAt=end;}
    BatchMarketRepository(JdbcTemplate db) {this(db,null);}
    BatchMarketRepository(JdbcTemplate db,BotLedgerJournal journal) {this.db=db;this.journal=journal;}

    BatchOrderBook load(long now) {
        return load(now,null,Set.of());
    }
    BatchOrderBook load(long now,Set<Long> symbols,Set<Long> participants) {
        lock(symbols);
        return loadLocked(now,symbols,participants);
    }
    void lock(Set<Long> symbols) {
        // Same admission gate and stock locks as user orders; disjoint symbol groups can proceed together.
        db.queryForObject("SELECT id FROM market_locks WHERE id=1 "+(symbols==null?"FOR UPDATE":"FOR SHARE"),Integer.class);
        String selected=symbols==null?"":symbols.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        db.queryForList("SELECT id FROM stocks "+(symbols==null?"":"WHERE id IN ("+selected+") ")+"ORDER BY id FOR UPDATE",Long.class);
    }
    private String selected(Set<Long> symbols) {return symbols.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));}
    Map<Long,Long> versions(Set<Long> symbols) {
        Map<Long,Long> result=new HashMap<>();
        db.query("SELECT stock_id,revision FROM market_book_revisions WHERE stock_id IN ("+selected(symbols)+")",rs->{result.put(rs.getLong(1),rs.getLong(2));});
        return result;
    }
    CommittedTradeCounter recentCounter(Set<Long> symbols,long now) {
        var counter=new CommittedTradeCounter();
        db.query("SELECT stock_id,created_at,COUNT(*) FROM trades WHERE stock_id IN ("+selected(symbols)+") AND created_at>=? GROUP BY stock_id,created_at",
                rs->{counter.add(rs.getLong(1),rs.getTimestamp(2).getTime(),rs.getInt(3));},new Timestamp(now-1000));
        db.query("SELECT stock_id,bucket_at,fills FROM bot_trade_seconds WHERE stock_id IN ("+selected(symbols)+") AND bucket_at>=?",
                rs->{counter.add(rs.getLong(1),rs.getLong(2)*1000,rs.getInt(3));},(now-1000)/1000);
        return counter;
    }
    BatchOrderBook loadLocked(long now,Set<Long> symbols,Set<Long> participants) {
        String selected=symbols==null?"":selected(symbols);
        boolean open="NORMAL".equals(db.queryForObject("SELECT phase FROM market_protection_state WHERE id=1",String.class));
        Map<Long,Stock> stocks=new LinkedHashMap<>();
        db.query("SELECT s.id,s.stock_code,s.current_price,p.day_reference,p.static_reference,p.dynamic_reference,p.vi_type,s.total_volume,g.name,g.genre,s.previous_price FROM stocks s JOIN games g ON g.id=s.game_id JOIN stock_protection_state p ON p.stock_id=s.id "+(symbols==null?"":"WHERE s.id IN ("+selected+") ")+"ORDER BY s.id",
                rs->{long id=rs.getLong(1);stocks.put(id,new Stock(id,rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),open&&rs.getString(7)==null));var stock=stocks.get(id);stock.totalVolume=rs.getLong(8);stock.name=rs.getString(9);stock.genre=rs.getString(10);stock.previous=rs.getLong(11);});
        List<Order> orders=db.query("SELECT id,user_id,stock_id,side,order_type,COALESCE(price,0),quantity,remaining_quantity,reserved_cash,reserved_quantity,status,created_at,expires_at,compact_origin FROM orders WHERE status='OPEN' "+(symbols==null?"":"AND stock_id IN ("+selected+") ")+"ORDER BY id",
                (rs,n)->{var o=new Order(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getLong(6),rs.getInt(7),rs.getInt(8),rs.getLong(9),rs.getInt(10),rs.getString(11),rs.getTimestamp(12).getTime(),rs.getTimestamp(13)==null?0:rs.getTimestamp(13).getTime());o.persisted=true;o.compactOrigin=rs.getBoolean(14);return o;});
        Set<Long> owners=new TreeSet<>(participants);orders.forEach(o->owners.add(o.user));
        String ownerIds=owners.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        // Plan against committed balances. Ordered account locks and minimum-cash validation at
        // persistence preserve every intermediate funding constraint, including across symbols.
        Map<Long,Account> accounts=new HashMap<>();
        db.query("SELECT id,cash,password_hash='BOT',password_hash IN ('BOT','TRADER') FROM users WHERE "+(symbols==null?"password_hash IN ('BOT','TRADER') OR id IN (SELECT user_id FROM orders WHERE status='OPEN')":owners.isEmpty()?"FALSE":"id IN ("+ownerIds+")")+" ORDER BY id",
                rs->{accounts.put(rs.getLong(1),new Account(rs.getLong(1),rs.getLong(2),rs.getBoolean(3),rs.getBoolean(4)));});
        Map<PositionKey,Holding> holdings=new HashMap<>();
        Map<PositionKey,Long> holdingIds=new HashMap<>();
        db.query("SELECT user_id,stock_id,quantity,settled_quantity,average_price,realized_profit_loss,id FROM portfolios "+(symbols==null?"":"WHERE stock_id IN ("+selected+")"),
                rs->{if(accounts.containsKey(rs.getLong(1))) {var key=new PositionKey(rs.getLong(1),rs.getLong(2));holdings.put(key,new Holding(rs.getInt(3),rs.getInt(4),rs.getLong(5),rs.getLong(6)));holdingIds.put(key,rs.getLong(7));}});
        long sequence=db.queryForObject("SELECT COALESCE(MAX(id),0) FROM orders",Long.class);
        var book=new BatchOrderBook(accounts,stocks,holdings,orders,sequence,now);book.holdingIds.putAll(holdingIds);return book;
    }

    void persist(BatchOrderBook book,long now) {
        measuredAt=System.nanoTime();
        Set<Long> lockIds=new TreeSet<>(book.changedAccounts);
        book.changedOrders.forEach(o->lockIds.add(o.user));
        if(!lockIds.isEmpty()) {
            String ids=lockIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            db.query("SELECT id,cash FROM users WHERE id IN ("+ids+") ORDER BY id FOR UPDATE",rs->{
                Account a=book.accounts.get(rs.getLong(1));long current=rs.getLong(2);
                if(current<a.openingCash-a.minimumCash)
                    throw new org.springframework.dao.CannotAcquireLockException("Concurrent cash use requires a fresh batch decision");
                a.cash=current+(a.cash-a.openingCash);a.openingCash=current;
            });
        }
        stamp("lockAccounts");
        Timestamp time=new Timestamp(now/1000*1000);
        List<Fill> compact=journal==null?List.of():book.fills.stream().filter(f->journal.compact(f,book)).toList();
        List<Fill> retained=journal==null?book.fills:book.fills.stream().filter(f->!journal.compact(f,book)).toList();
        Set<Order> referenced=new HashSet<>();retained.forEach(f->{referenced.add(f.buy());referenced.add(f.sell());});
        Set<Order> fresh=new HashSet<>(book.accepted);
        List<Order> persistent=book.accepted.stream().filter(o->journal==null||o.status.equals("OPEN")||referenced.contains(o)||!book.accounts.get(o.user).bot).toList();
        List<Long> orderIds=insert("orders","user_id,stock_id,side,order_type,price,quantity,remaining_quantity,reserved_cash,reserved_quantity,status,created_at,expires_at,compact_origin",
                persistent.stream().map(o->new Object[]{o.user,o.stock,o.side,o.type,o.market()&&o.price==0?null:o.price,o.quantity,o.remaining,o.reservedCash,o.reservedQuantity,o.status,new Timestamp(o.createdAt),o.expiresAt==0?null:new Timestamp(o.expiresAt),journal!=null&&book.accounts.get(o.user).bot}).toList(),true,"");
        for(int i=0;i<orderIds.size();i++){Order o=persistent.get(i);o.id=orderIds.get(i);o.persisted=true;o.compactOrigin=journal!=null&&book.accounts.get(o.user).bot;}
        // Transient, completed bot orders have a journal-local negative ID, distinct from SQL IDs.
        if(journal!=null)book.accepted.stream().filter(o->!o.persisted).forEach(o->o.id=-o.id);
        stamp("newOrders");
        insert("orders","id,user_id,stock_id,side,order_type,price,quantity,remaining_quantity,reserved_cash,reserved_quantity,status,created_at,expires_at",
                book.changedOrders.stream().filter(o->o.persisted&&!fresh.contains(o)).map(o->new Object[]{o.id,o.user,o.stock,o.side,o.type,o.market()&&o.price==0?null:o.price,o.quantity,o.remaining,o.reservedCash,o.reservedQuantity,o.status,new Timestamp(o.createdAt),o.expiresAt==0?null:new Timestamp(o.expiresAt)}).toList(),false,
                " ON DUPLICATE KEY UPDATE price=VALUES(price),remaining_quantity=VALUES(remaining_quantity),reserved_cash=VALUES(reserved_cash),reserved_quantity=VALUES(reserved_quantity),status=VALUES(status)");
        stamp("oldOrders");
        update("users","id",new ArrayList<>(book.changedAccounts),id->id,List.of("cash"),id->new Object[]{book.accounts.get(id).cash});
        stamp("cash");
        // Updating a known primary key avoids MySQL's secondary-unique upsert gap locks across symbols.
        List<PositionKey> positions=book.changedHoldings.stream().sorted(Comparator.comparingLong(PositionKey::user).thenComparingLong(PositionKey::stock)).toList();
        update("portfolios","id",positions.stream().filter(book.holdingIds::containsKey).toList(),book.holdingIds::get,
                List.of("quantity","settled_quantity","average_price","realized_profit_loss"),k->{Holding h=book.holdings.get(k);return new Object[]{h.quantity(),h.settled(),h.average(),h.realized()};});
        var newPositions=positions.stream().filter(k->!book.holdingIds.containsKey(k)).toList();
        var positionIds=insert("portfolios","user_id,stock_id,quantity,settled_quantity,average_price,realized_profit_loss",newPositions.stream().map(k->{Holding h=book.holdings.get(k);return new Object[]{k.user(),k.stock(),h.quantity(),h.settled(),h.average(),h.realized()};}).toList(),true,"");
        for(int i=0;i<positionIds.size();i++)book.holdingIds.put(newPositions.get(i),positionIds.get(i));
        stamp("holdings");
        List<Long> tradeIds=insert("trades","stock_id,buy_order_id,sell_order_id,buyer_id,seller_id,maker_order_id,taker_order_id,aggressor_side,quantity,price,buyer_fee,seller_fee,fee,created_at",
                retained.stream().map(f->new Object[]{f.buy().stock,f.buy().id,f.sell().id,f.buy().user,f.sell().user,f.maker().id,f.taker().id,f.taker().side,f.quantity(),f.price(),f.buyerFee(),f.sellerFee(),f.buyerFee()+f.sellerFee(),time}).toList(),true,"");
        stamp("trades");
        List<Object[]> settlements=new ArrayList<>();
        for(int i=0;i<retained.size();i++) {
            Fill f=retained.get(i);Holding b=f.buyerBefore(),s=f.sellerBefore();
            settlements.add(new Object[]{tradeIds.get(i),f.buy().user,f.sell().user,f.buy().stock,f.quantity(),f.price()*f.quantity(),f.buyerFee(),f.sellerFee(),time,"SETTLED",b.quantity(),b.settled(),b.average(),b.realized(),s.quantity(),s.settled(),s.average(),s.realized(),time,time});
        }
        insert("settlements","trade_id,buyer_id,seller_id,stock_id,quantity,gross_amount,buyer_fee,seller_fee,settlement_at,status,buyer_quantity_before,buyer_settled_quantity_before,buyer_average_price_before,buyer_realized_profit_loss_before,seller_quantity_before,seller_settled_quantity_before,seller_average_price_before,seller_realized_profit_loss_before,settled_at,created_at",settlements,false,"");
        stamp("settlements");
        if(journal!=null) {
            journal.write(book,compact,now);
            journal.deleteOrders(book.changedOrders.stream().filter(o->o.persisted&&o.compactOrigin&&!o.status.equals("OPEN")).map(o->o.id).toList());
        }
        stamp("journal");
        Map<Long,PriceChange> changes=new LinkedHashMap<>();
        for(PriceChange p:book.prices) {PriceChange old=changes.get(p.stock());changes.put(p.stock(),new PriceChange(p.stock(),p.previous(),p.price(),p.volume()+(old==null?0:old.volume())));}
        update("stocks","id",new ArrayList<>(changes.values()),PriceChange::stock,List.of("previous_price","current_price","total_volume"),p->new Object[]{p.previous(),p.price(),p.volume()},Set.of("total_volume"));
        insert("stock_price_history","stock_id,price,recorded_at",journal==null?book.prices.stream().map(p->new Object[]{p.stock(),p.price(),time}).toList():retained.stream().map(f->new Object[]{f.buy().stock,f.price(),time}).toList(),false,"");
        String tradingDate=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        insert("daily_market_summaries","stock_id,trading_date,open_price,close_price,total_volume",changes.values().stream().map(p->new Object[]{p.stock(),tradingDate,p.price(),p.price(),p.volume()}).toList(),false,
                " ON DUPLICATE KEY UPDATE close_price=VALUES(close_price),total_volume=daily_market_summaries.total_volume+VALUES(total_volume)");
        update("stock_protection_state","stock_id",new ArrayList<>(changes.values()),PriceChange::stock,List.of("last_trade_price","dynamic_reference"),p->new Object[]{p.price(),book.stocks.get(p.stock()).dynamicReference});
        stamp("prices");
        db.update("UPDATE market_book_revisions SET revision=revision+1 WHERE stock_id IN ("+selected(book.stocks.keySet())+")");
    }

    List<Long> insert(String table,String columns,List<Object[]> rows,boolean keys,String suffix) {
        List<Long> result=new ArrayList<>();
        for(int start=0;start<rows.size();) {
            int size=Integer.highestOneBit(Math.min(128,rows.size()-start));
            List<Object[]> chunk=rows.subList(start,start+size);start+=size;
            String values="("+String.join(",",Collections.nCopies(chunk.get(0).length,"?"))+")";
            String sql="INSERT INTO "+table+" ("+columns+") VALUES "+String.join(",",Collections.nCopies(chunk.size(),values))+suffix;
            db.execute((ConnectionCallback<Void>)connection->{
                try(PreparedStatement statement=connection.prepareStatement(sql,keys?Statement.RETURN_GENERATED_KEYS:Statement.NO_GENERATED_KEYS)) {
                    int index=1;for(Object[] row:chunk)for(Object value:row)bind(statement,index++,value);
                    statement.executeUpdate();
                    if(keys)try(ResultSet ids=statement.getGeneratedKeys()) {
                        int count=0;while(ids.next()){result.add(ids.getLong(1));count++;}
                        if(count!=chunk.size())throw new SQLException("Incomplete generated keys for "+table+": "+count+"/"+chunk.size());
                    }
                }
                return null;
            });
        }
        return result;
    }
    private <T> void update(String table,String key,List<T> rows,Function<T,Long> id,List<String> columns,Function<T,Object[]> values) {
        update(table,key,rows,id,columns,values,Set.of());
    }
    private <T> void update(String table,String key,List<T> rows,Function<T,Long> id,List<String> columns,Function<T,Object[]> values,Set<String> add) {
        for(int start=0;start<rows.size();) {
            int size=Integer.highestOneBit(Math.min(128,rows.size()-start));
            List<T> chunk=rows.subList(start,start+size);start+=size;
            List<String> assignments=new ArrayList<>();List<Object> args=new ArrayList<>();
            for(int c=0;c<columns.size();c++) {
                String column=columns.get(c);StringBuilder expression=new StringBuilder(column+"="+(add.contains(column)?column+"+":"")+"CASE "+key);
                for(T row:chunk){expression.append(" WHEN ? THEN ?");args.add(id.apply(row));args.add(values.apply(row)[c]);}
                expression.append(" END");assignments.add(expression.toString());
            }
            for(T row:chunk)args.add(id.apply(row));
            String sql="UPDATE "+table+" SET "+String.join(",",assignments)+" WHERE "+key+" IN ("+String.join(",",Collections.nCopies(chunk.size(),"?"))+")";
            db.execute((ConnectionCallback<Void>)connection->{
                try(PreparedStatement statement=connection.prepareStatement(sql)) {
                    int index=1;for(Object value:args)bind(statement,index++,value);statement.executeUpdate();
                }
                return null;
            });
        }
    }
    private static void bind(PreparedStatement statement,int index,Object value) throws SQLException {
        if(value==null)statement.setNull(index,Types.NULL);
        else if(value instanceof Long number)statement.setLong(index,number);
        else if(value instanceof Integer number)statement.setInt(index,number);
        else if(value instanceof String string)statement.setString(index,string);
        else if(value instanceof Timestamp time)statement.setTimestamp(index,time);
        else if(value instanceof byte[] bytes)statement.setBytes(index,bytes);
        else if(value instanceof Boolean bool)statement.setBoolean(index,bool);
        else if(value instanceof java.sql.Date date)statement.setDate(index,date);
        else statement.setObject(index,value);
    }

}
