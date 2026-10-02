package com.gamestock.backend.market;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.gamestock.backend.market.MarketEnvironment.clamp;

/** Reads executed trades only. Sparse history returns neutral indicators, never synthetic candles. */
public final class PriceMetricService {
    /** A print or an observed one-second bucket; notional/extremes retain every real fill. */
    public record Trade(long time, double price, long quantity, double notional, double high, double low) {
        public Trade(long time,double price,long quantity) { this(time,price,quantity,price*quantity,price,price); }
    }
    public record Metrics(double lastPrice, double midPrice, double microPrice, double vwap, double markPrice,
            double bestBid, double bestAsk, long bidDepth, long askDepth, double imbalance,
            double return5s, double return20s, double emaSlope, double recentHigh, double recentLow,
            double volumeTrend, double acceleration, double rsi, double zscore, double volatility, long volume,
            double return60s, double return300s, double referenceVwap) {
        public double spread() { return Math.max(0,bestAsk-bestBid); }
    }
    private final JdbcTemplate jdbc;
    public PriceMetricService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    void ensureTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS market_trade_buckets (stock_id BIGINT NOT NULL,created_at TIMESTAMP NOT NULL,last_id BIGINT NOT NULL,quantity BIGINT NOT NULL,notional DOUBLE NOT NULL,high BIGINT NOT NULL,low BIGINT NOT NULL,PRIMARY KEY(stock_id,created_at))");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='market_trade_buckets' AND index_name='ix_retention_time'",Integer.class)==0)
            jdbc.execute("CREATE INDEX ix_retention_time ON market_trade_buckets(created_at)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS market_metric_cursors (stock_id BIGINT PRIMARY KEY,last_id BIGINT NOT NULL DEFAULT 0)");
        jdbc.update("INSERT IGNORE INTO market_metric_cursors SELECT id,0 FROM stocks");
    }
    /** Incremental projection under the caller's symbol/gate lock, in the same ledger transaction. */
    void project(long stockId,long now) {
        long cursor=jdbc.queryForObject("SELECT last_id FROM market_metric_cursors WHERE stock_id=?",Long.class,stockId);
        long end=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM trades WHERE stock_id=?",Long.class,stockId);
        if(end<=cursor)return;
        jdbc.update("""
                INSERT INTO market_trade_buckets(stock_id,created_at,last_id,quantity,notional,high,low)
                SELECT stock_id,created_at,MAX(id),SUM(quantity),SUM(price*quantity),MAX(price),MIN(price)
                FROM trades t WHERE stock_id=? AND id>? AND id<=? AND created_at>=?
                  AND NOT EXISTS (SELECT 1 FROM settlements s WHERE s.trade_id=t.id AND s.status='CANCELLED')
                GROUP BY stock_id,created_at
                ON DUPLICATE KEY UPDATE last_id=VALUES(last_id),quantity=market_trade_buckets.quantity+VALUES(quantity),
                  notional=market_trade_buckets.notional+VALUES(notional),high=GREATEST(high,VALUES(high)),low=LEAST(low,VALUES(low))
                """,stockId,cursor,end,new java.sql.Timestamp(now-600_000));
        jdbc.update("UPDATE market_metric_cursors SET last_id=? WHERE stock_id=?",end,stockId);
    }
    void invalidate(long stockId) {
        jdbc.update("DELETE FROM market_trade_buckets WHERE stock_id=?",stockId);
        jdbc.update("UPDATE market_metric_cursors SET last_id=0 WHERE stock_id=?",stockId);
    }
    public Metrics read(long stockId, long last, long now, long latency) {
        long cutoff=now-latency;
        List<Trade> trades=jdbc.query("""
                SELECT b.created_at,last.price,b.quantity,b.notional,b.high,b.low
                FROM (
                  SELECT created_at,MAX(last_id) last_id,SUM(quantity) quantity,SUM(notional) notional,MAX(high) high,MIN(low) low
                  FROM (
                    SELECT created_at,last_id,quantity,notional,high,low FROM market_trade_buckets
                    WHERE stock_id=? AND created_at>=? AND created_at<=?
                    UNION ALL
                    SELECT t.created_at,t.id,t.quantity,t.price*t.quantity,t.price,t.price FROM trades t
                    WHERE t.stock_id=? AND t.id>(SELECT last_id FROM market_metric_cursors WHERE stock_id=?)
                      AND t.created_at>=? AND t.created_at<=?
                      AND NOT EXISTS (SELECT 1 FROM settlements s WHERE s.trade_id=t.id AND s.status='CANCELLED')
                  ) observed GROUP BY created_at
                ) b JOIN trades last ON last.id=b.last_id ORDER BY b.created_at
                """, (rs,n)->new Trade(rs.getTimestamp(1).getTime(),rs.getDouble(2),rs.getLong(3),rs.getDouble(4),rs.getDouble(5),rs.getDouble(6)),
                stockId,new java.sql.Timestamp(cutoff-600_000),new java.sql.Timestamp(cutoff),stockId,stockId,new java.sql.Timestamp(cutoff-600_000),new java.sql.Timestamp(cutoff));
        trades=new ArrayList<>(trades);
        trades.addAll(new BotLedgerJournal(jdbc).observations(stockId,cutoff));
        trades.sort(Comparator.comparingLong(Trade::time));
        List<long[]> bids=levels(stockId,"BUY"), asks=levels(stockId,"SELL");
        double bid=bids.isEmpty()?last:bids.get(0)[0], ask=asks.isEmpty()?last:asks.get(0)[0];
        long bd=bids.stream().mapToLong(a->a[1]).sum(), ad=asks.stream().mapToLong(a->a[1]).sum();
        return calculate(trades,last,bid,ask,bd,ad,cutoff);
    }
    private List<long[]> levels(long id,String side) {
        return jdbc.query("SELECT price,SUM(remaining_quantity) FROM orders WHERE stock_id=? AND side=? "
                +"AND status='OPEN' AND order_type='LIMIT' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP(3)) "
                +"GROUP BY price ORDER BY price "+("BUY".equals(side)?"DESC":"ASC")+" LIMIT 5",
                (rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},id,side);
    }
    public static Metrics calculate(List<Trade> trades,double fallback,double bid,double ask,long bd,long ad,long now) {
        double last=trades.isEmpty()?fallback:trades.get(trades.size()-1).price;
        double mid=bd>0&&ad>0?(bid+ask)/2:last;
        double micro=bd>0&&ad>0?(ask*bd+bid*ad)/(bd+ad):mid;
        double notional=0, referenceNotional=0, mean=0, sq=0, gain=0, loss=0;
        double high=Double.NEGATIVE_INFINITY, low=Double.POSITIVE_INFINITY, p5=last, p20=last, retSq=0;
        double p60=last,p300=last;
        long volume=0, referenceVolume=0, recentVol=0, priorVol=0;
        for(Trade t:trades) {
            if(t.time>=now-600_000 && t.time<=now) {
                referenceNotional+=t.notional; referenceVolume+=t.quantity;
            }
            if(t.time<=now-5_000) p5=t.price;
            if(t.time<=now-20_000) p20=t.price;
            if(t.time<=now-60_000) p60=t.price;
            if(t.time<=now-300_000) p300=t.price;
            if(t.time<now-60_000) continue;
            notional+=t.notional; volume+=t.quantity;
            if(t.time<now-5_000) { high=Math.max(high,t.high); low=Math.min(low,t.low); }
            if(t.time>=now-20_000) recentVol+=t.quantity; else if(t.time>=now-40_000) priorVol+=t.quantity;
        }
        // One-second observed-price samples over a TIME window, not the last 60 trades.
        // Carry forward only known fills for indicators; these samples never write market prices/history.
        // This also prevents comparing a truncated window's first sample to a ten-minute-old price.
        long windowStart=now-60_000;
        if(!trades.isEmpty()) windowStart=Math.max(windowStart,trades.get(0).time);
        int cursor=0;
        double observed=trades.isEmpty()?last:trades.get(0).price;
        while(cursor<trades.size() && trades.get(cursor).time<=windowStart) observed=trades.get(cursor++).price;
        double prev=observed, ema=observed, oldEma=observed;
        int n=0;
        for(long time=windowStart;time<=now;time+=1000) {
            while(cursor<trades.size() && trades.get(cursor).time<=time) observed=trades.get(cursor++).price;
            double p=observed;
            double d=p-prev; gain+=Math.max(d,0); loss+=Math.max(-d,0);
            retSq+=Math.pow(d/Math.max(1,prev),2); mean+=p; sq+=p*p; n++;
            oldEma=ema; ema=.2*p+.8*ema; prev=p;
        }
        if(!Double.isFinite(high)) high=last;
        if(!Double.isFinite(low)) low=last;
        mean=n==0?last:mean/n;
        double sd=Math.sqrt(Math.max(0,n==0?0:sq/n-mean*mean));
        double vwap=volume==0?last:notional/volume;
        // A tiny terminal print has at most 10% weight; book influence is capped around executed VWAP.
        double mark=volume==0?last:.7*vwap+.2*clamp(mid,vwap*.99,vwap*1.01)+.1*clamp(last,vwap*.99,vwap*1.01);
        return new Metrics(last,mid,micro,vwap,mark,bid,ask,bd,ad,(bd-ad)/(double)Math.max(1,bd+ad),
                last/Math.max(1,p5)-1,last/Math.max(1,p20)-1,(ema-oldEma)/Math.max(1,oldEma),high,low,
                priorVol==0?0:clamp(recentVol/(double)priorVol-1,-1,3),
                (last/Math.max(1,p5)-1)-(last/Math.max(1,p20)-1)/4,
                gain+loss==0?50:100*gain/(gain+loss),sd<.001?0:(last-mean)/sd,
                n<2?0:Math.sqrt(retSq/(n-1)),volume,last/Math.max(1,p60)-1,last/Math.max(1,p300)-1,
                referenceVolume==0?last:referenceNotional/referenceVolume);
    }
}
