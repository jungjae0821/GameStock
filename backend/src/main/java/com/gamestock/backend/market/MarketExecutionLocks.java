package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.util.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;
import static com.gamestock.backend.market.MarketModels.*;

/** Database locks live until commit, including across backend instances. */
final class MarketExecutionLocks {
    private MarketExecutionLocks() { }

    record LockedSymbol(long id,long lastPrice,Timestamp acceptedAt,Set<Long> accounts) { }
    record Resting(long id,long user,String side,long price,int remaining,int reservedQuantity,
                   boolean market,boolean expired) { }

    static LockedSymbol forOrder(JdbcTemplate jdbc,String code,long userId,OrderRequest request) {
        // Normal orders share the gate. Maintenance/cancel/auction take its exclusive lock.
        jdbc.queryForObject("SELECT id FROM market_locks WHERE id=1 FOR SHARE",Integer.class);
        var stocks=jdbc.query("SELECT id,current_price,CURRENT_TIMESTAMP(3) FROM stocks WHERE stock_code=? FOR UPDATE",
                (rs,n)->new LockedSymbol(rs.getLong(1),rs.getLong(2),rs.getTimestamp(3),Set.of()),code);
        if(stocks.isEmpty()) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        LockedSymbol stock=stocks.get(0);
        MarketBookCache.invalidate(jdbc,stock.id());
        // The symbol lock already protects this book. Do not lock an optimizer-chosen range
        // in the shared orders table (it can scan another symbol). Orders use READ_COMMITTED.
        var rows=jdbc.query("""
                SELECT id,user_id,side,COALESCE(price,0),remaining_quantity,reserved_quantity,
                       order_type='MARKET',expires_at IS NOT NULL AND expires_at<=?
                FROM orders WHERE stock_id=? AND status='OPEN' ORDER BY created_at,id
                """,(rs,n)->new Resting(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getLong(4),rs.getInt(5),rs.getInt(6),rs.getBoolean(7),rs.getBoolean(8)),stock.acceptedAt(),stock.id());
        long reference=jdbc.queryForObject("SELECT COALESCE((SELECT open_price FROM daily_market_summaries WHERE stock_id=? AND trading_date=CURRENT_DATE),?)",Long.class,stock.id(),stock.lastPrice());
        var accounts=accountsFor(request,userId,rows,reference,stock.lastPrice());
        // The same account may trade several symbols. Always acquire accounts in numeric order.
        for(long account:accounts) jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,account);
        return new LockedSymbol(stock.id(),stock.lastPrice(),stock.acceptedAt(),Set.copyOf(accounts));
    }

    static SortedSet<Long> accountsFor(OrderRequest request,long user,List<Resting> rows,long reference,long last) {
        SortedSet<Long> result=new TreeSet<>();result.add(user);
        rows.stream().filter(Resting::expired).forEach(o->result.add(o.user()));
        var live=rows.stream().filter(o->!o.expired() && o.remaining()>0).toList();
        long bid=live.stream().filter(o->o.side().equals("BUY")).mapToLong(Resting::price).max().orElse(0);
        long ask=live.stream().filter(o->o.side().equals("SELL")).mapToLong(Resting::price).min().orElse(Long.MAX_VALUE);
        // Legacy crossed/market/unreserved books may cancel a maker and expose a later order.
        // The conservative path locks the entire live book; ordinary continuous books stay narrow.
        if(bid>=ask || live.stream().anyMatch(o->o.market() || o.side().equals("SELL") && o.reservedQuantity()<o.remaining())) {
            live.forEach(o->result.add(o.user()));return result;
        }
        boolean buy="BUY".equalsIgnoreCase(request.side());
        boolean market=request.orderType()==null || request.orderType().isBlank() || "MARKET".equalsIgnoreCase(request.orderType());
        long limit=market?(buy?Long.MAX_VALUE:0):request.price()==null?0:request.price();
        if(!buy) {
            // Rounded fees can make a fully reserved buyer unaffordable after a partial fill.
            // Include later crossing buyers as well in case the matcher cancels such an order.
            live.stream().filter(o->o.side().equals("BUY") && o.price()>=limit).forEach(o->result.add(o.user()));
            return result;
        }
        long remaining=Math.max(0,request.quantity());
        // Stable sort preserves price-time order from the DB, including equal timestamps.
        for(Resting sell:live.stream().filter(o->o.side().equals("SELL") && o.price()<=limit)
                .sorted(Comparator.comparingLong(Resting::price)).toList()) {
            if(remaining<=0) break;
            result.add(sell.user());
            if(sell.user()==user) continue; // STP may cancel either row if a legacy timestamp is ahead.
            // Quotes outside any execution band might be cancelled instead of consumed.
            // Counting only this safe intersection gives a conservative superset of counterparties.
            if(dailyBand(reference).contains(sell.price()) && botBand(reference).contains(sell.price())
                    && (!market || marketExecutionBand(last).contains(sell.price()))) remaining-=sell.remaining();
        }
        return result;
    }
}
