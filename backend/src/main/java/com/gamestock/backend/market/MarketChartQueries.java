package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.gamestock.backend.market.MarketModels.*;

/**
 * Read-only chart, quote and trade-print queries split out of {@link MarketService}.
 * Not a Spring bean: MarketService constructs it and keeps the public entry points,
 * so their {@code @Transactional} proxies and {@code synchronized} monitor stay on
 * MarketService. Clock and journal are read through MarketService on every call
 * because tests and startup replace them after construction.
 */
final class MarketChartQueries {
    private final MarketService market;
    private final JdbcTemplate jdbc;

    MarketChartQueries(MarketService market, JdbcTemplate jdbc) {
        this.market = market;
        this.jdbc = jdbc;
    }

    /** 모든 사용자의 익명 체결 내역. 개인별 주문 API와 분리해 공개한다. */
    public List<PublicTrade> publicTrades(String code) {
        List<PublicTrade> result=new ArrayList<>(jdbc.query("""
                SELECT t.aggressor_side AS side, t.quantity, t.price, taker.order_type, t.created_at
                FROM trades t
                JOIN stocks s ON s.id = t.stock_id
                JOIN orders taker ON taker.id = t.taker_order_id
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE s.stock_code = ? AND t.price_scale=1 AND (st.id IS NULL OR st.status <> 'CANCELLED')
                ORDER BY t.created_at DESC, t.id DESC LIMIT 10
                """, (rs, row) -> new PublicTrade(rs.getString("side"), rs.getInt("quantity"),
                rs.getLong("price"), rs.getString("order_type"), market.databaseInstant(rs.getTimestamp("created_at")).toString()),
                code.toUpperCase(Locale.ROOT)));
        var ids=jdbc.queryForList("SELECT id FROM stocks WHERE stock_code=?",Long.class,code.toUpperCase(Locale.ROOT));
        if(market.botJournal()!=null&&!ids.isEmpty())for(var print:market.botJournal().recent(ids.get(0)))
            result.add(new PublicTrade(print.side(),print.quantity(),print.price(),print.type(),Instant.ofEpochMilli(print.at()).toString()));
        return result.stream().sorted(Comparator.comparing((PublicTrade trade)->Instant.parse(trade.createdAt())).reversed()).limit(10).toList();
    }

    public List<PricePoint> priceHistory(String code) {
        return priceHistory(code, "24h");
    }

    /**
     * Returns the recorded price points inside one of the public chart windows.
     * The range is deliberately allow-listed so a client cannot inject SQL
     * interval fragments. A generous point cap keeps a week view lightweight;
     * the clients down-sample only when the canvas width requires it.
     */
    public List<PricePoint> priceHistory(String code, String range) {
        Instant cutoff = market.clock().instant().minus(historyWindow(range));
        String table=historyWindow(range).toMinutes()<=15?"bot_trade_seconds":"bot_trade_minutes";
        List<PricePoint> points = jdbc.query("""
                SELECT price,recorded_at FROM (
                  SELECT h.execution_sequence AS id,h.price,h.recorded_at FROM stock_price_history h JOIN stocks s ON s.id=h.stock_id
                  WHERE s.stock_code=? AND h.recorded_at>=?
                  UNION ALL
                  SELECT b.last_sequence,b.close_price,FROM_UNIXTIME(b.last_at/1000.0) FROM %s b JOIN stocks s ON s.id=b.stock_id
                  WHERE s.stock_code=? AND b.last_at>=?
                ) points ORDER BY recorded_at DESC,id DESC
                LIMIT 2000
                """.formatted(table), (rs, row) -> new PricePoint(
                rs.getLong("price"),
                market.databaseInstant(rs.getTimestamp("recorded_at")).toString()),
                code.toUpperCase(Locale.ROOT), Timestamp.from(cutoff),code.toUpperCase(Locale.ROOT),cutoff.toEpochMilli());
        Collections.reverse(points);
        return points;
    }

    /** Returns a bounded OHLC series for the chart ranges shown by the client. */
    public List<ChartCandle> chartHistory(String code, String range) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (market.findStock(normalized) == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        Duration window = chartWindow(range);
        long bucketSeconds = chartBucketSeconds(range);
        Instant cutoff = market.clock().instant().minus(window);
        String table=window.toDays()<=8?"bot_trade_minutes":"bot_trade_hours";
        return jdbc.query("""
                WITH points AS (
                  SELECT h.execution_sequence AS id,h.execution_sequence AS first_sequence,h.price open_price,h.price high_price,h.price low_price,h.price close_price,h.recorded_at,
                         UNIX_TIMESTAMP(h.recorded_at)*1000 first_at,UNIX_TIMESTAMP(h.recorded_at)*1000 last_at,1 point_count
                  FROM stock_price_history h JOIN stocks s ON s.id=h.stock_id WHERE s.stock_code=? AND h.recorded_at>=?
                  UNION ALL
                  SELECT b.last_sequence,b.first_sequence,b.open_price,b.high_price,b.low_price,b.close_price,FROM_UNIXTIME(b.bucket_at),b.first_at,b.last_at,b.fills
                  FROM %s b JOIN stocks s ON s.id=b.stock_id WHERE s.stock_code=? AND b.bucket_at>=?
                ), bucketed AS (
                  SELECT h.*,
                         FLOOR(UNIX_TIMESTAMP(h.recorded_at) / ?) AS bucket,
                         ROW_NUMBER() OVER (
                           PARTITION BY FLOOR(UNIX_TIMESTAMP(h.recorded_at) / ?)
                           ORDER BY h.first_at ASC, h.first_sequence ASC
                         ) AS open_rank,
                         ROW_NUMBER() OVER (
                           PARTITION BY FLOOR(UNIX_TIMESTAMP(h.recorded_at) / ?)
                           ORDER BY h.last_at DESC, h.id DESC
                         ) AS close_rank
                  FROM points h
                )
                SELECT bucket * ? AS bucket_at,
                       MAX(CASE WHEN open_rank = 1 THEN open_price END) AS open_price,
                       MAX(high_price) AS high_price,
                       MIN(low_price) AS low_price,
                       MAX(CASE WHEN close_rank = 1 THEN close_price END) AS close_price,
                       SUM(point_count) AS point_count,
                       MAX(last_at) AS updated_at
                FROM bucketed
                GROUP BY bucket
                ORDER BY bucket ASC
                LIMIT 2000
                """.formatted(table), (rs, row) -> new ChartCandle(
                Instant.ofEpochSecond(rs.getLong("bucket_at")).toString(),
                rs.getLong("open_price"),
                rs.getLong("high_price"),
                rs.getLong("low_price"),
                rs.getLong("close_price"),
                rs.getLong("point_count"),
                Instant.ofEpochMilli(rs.getLong("updated_at")).toString()),
                normalized,Timestamp.from(cutoff),normalized,cutoff.getEpochSecond(),bucketSeconds,bucketSeconds,bucketSeconds,bucketSeconds);
    }

    private Duration chartWindow(String range) {
        return switch (range == null ? "1d" : range.trim().toLowerCase(Locale.ROOT)) {
            case "1h" -> Duration.ofHours(1);
            case "6h" -> Duration.ofHours(6);
            case "12h" -> Duration.ofHours(12);
            case "1d" -> Duration.ofDays(1);
            case "3d" -> Duration.ofDays(3);
            case "1w" -> Duration.ofDays(7);
            case "2w" -> Duration.ofDays(14);
            case "1m" -> Duration.ofDays(30);
            case "1y" -> Duration.ofDays(365);
            default -> Duration.ofDays(1);
        };
    }

    private long chartBucketSeconds(String range) {
        return switch (range == null ? "1d" : range.trim().toLowerCase(Locale.ROOT)) {
            case "1h" -> 60;
            case "6h" -> 300;
            case "12h" -> 600;
            case "1d" -> 1800;
            case "3d" -> 3600;
            case "1w" -> 7200;
            case "2w" -> 21600;
            case "1m" -> 86400;
            case "1y" -> 604800;
            default -> 1800;
        };
    }

    private Duration historyWindow(String range) {
        return switch (range == null ? "24h" : range.trim().toLowerCase(Locale.ROOT)) {
            case "5m" -> Duration.ofMinutes(5);
            case "10m" -> Duration.ofMinutes(10);
            case "30m" -> Duration.ofMinutes(30);
            case "1h" -> Duration.ofHours(1);
            case "6h" -> Duration.ofHours(6);
            case "12h" -> Duration.ofHours(12);
            case "7d", "1w" -> Duration.ofDays(7);
            case "24h" -> Duration.ofHours(24);
            default -> Duration.ofHours(24);
        };
    }

    /**
     * Returns a transparent, public summary of the inputs that have shaped a
     * stock recently. This deliberately uses existing news, trade, and order
     * tables so the explanation is derived from the same data as the price.
     */
    public PriceDrivers priceDrivers(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        Stock stock = market.findStock(normalized);
        if (stock == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        long id = market.stockId(normalized);
        Double impact = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.impact * GREATEST(0, LEAST(1,
                    1 - TIMESTAMPDIFF(SECOND, COALESCE(e.published_at, e.created_at), CURRENT_TIMESTAMP) / 86400.0))), 0)
                FROM market_events e
                WHERE e.stock_id = ? AND e.event_type = 'NEWS'
                  AND COALESCE(e.published_at, e.created_at) >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                """, Double.class, id);
        Integer newsCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM market_events
                WHERE stock_id = ? AND event_type = 'NEWS'
                  AND COALESCE(published_at, created_at) >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                """, Integer.class, id);
        VolumeBreakdown volumes = jdbc.queryForObject("""
                SELECT
                  COALESCE(SUM(CASE WHEN buyer.password_hash NOT IN ('BOT','TRADER') THEN t.quantity/t.price_scale ELSE 0 END), 0) AS user_buy,
                  COALESCE(SUM(CASE WHEN seller.password_hash NOT IN ('BOT','TRADER') THEN t.quantity/t.price_scale ELSE 0 END), 0) AS user_sell,
                  COALESCE(SUM(CASE WHEN buyer.password_hash IN ('BOT','TRADER') THEN t.quantity/t.price_scale ELSE 0 END), 0) AS bot_buy,
                  COALESCE(SUM(CASE WHEN seller.password_hash IN ('BOT','TRADER') THEN t.quantity/t.price_scale ELSE 0 END), 0) AS bot_sell
                FROM trades t
                JOIN users buyer ON buyer.id = t.buyer_id
                JOIN users seller ON seller.id = t.seller_id
                WHERE t.stock_id = ?
                  AND t.created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                """, (rs, row) -> new VolumeBreakdown(rs.getLong("user_buy"), rs.getLong("user_sell"),
                rs.getLong("bot_buy"), rs.getLong("bot_sell")), id);
        long compactVolume=jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM bot_trade_minutes WHERE stock_id=? AND bucket_at>=?",Long.class,id,market.clock().millis()/1000-86400);
        volumes=new VolumeBreakdown(volumes.userBuy(),volumes.userSell(),volumes.botBuy()+compactVolume,volumes.botSell()+compactVolume);
        VolumeBreakdown openOrders = jdbc.queryForObject("""
                SELECT
                  COALESCE(SUM(CASE WHEN side = 'BUY' THEN remaining_quantity ELSE 0 END), 0) AS open_buy,
                  COALESCE(SUM(CASE WHEN side = 'SELL' THEN remaining_quantity ELSE 0 END), 0) AS open_sell
                FROM orders
                WHERE stock_id = ? AND status = 'OPEN'
                """, (rs, row) -> new VolumeBreakdown(rs.getLong("open_buy"), rs.getLong("open_sell"), 0, 0), id);
        Timestamp latestNews = jdbc.queryForObject("""
                SELECT MAX(COALESCE(published_at, created_at)) FROM market_events
                WHERE stock_id = ? AND event_type = 'NEWS'
                """, Timestamp.class, id);
        Timestamp latestTrade = jdbc.queryForObject("SELECT MAX(created_at) FROM trades WHERE stock_id = ?", Timestamp.class, id);
        Timestamp compactLatest=jdbc.queryForObject("SELECT MAX(last_trade) FROM bot_ledger_totals WHERE stock_id=?",Timestamp.class,id);
        if(compactLatest!=null&&(latestTrade==null||compactLatest.after(latestTrade)))latestTrade=compactLatest;
        double newsImpact = Math.max(-10.0, Math.min(10.0, impact == null ? 0.0 : impact));
        long userNet = volumes.userBuy() - volumes.userSell();
        long botNet = volumes.botBuy() - volumes.botSell();
        long openNet = openOrders.userBuy() - openOrders.userSell();
        return new PriceDrivers(normalized, stock.price(), previousPrice(normalized), stock.changePercent(),
                newsImpact, newsCount == null ? 0 : newsCount, volumes.userBuy(), volumes.userSell(),
                volumes.botBuy(), volumes.botSell(), openOrders.userBuy(), openOrders.userSell(),
                latestNews == null ? null : market.databaseInstant(latestNews).toString(),
                latestTrade == null ? null : market.databaseInstant(latestTrade).toString(),
                priceDriversReason(stock.changePercent(), newsImpact, userNet, botNet, openNet));
    }

    private long previousPrice(String code) {
        Long previous = jdbc.queryForObject("""
                SELECT COALESCE(p.day_reference, s.current_price)
                FROM stocks s
                LEFT JOIN stock_protection_state p ON p.stock_id = s.id
                WHERE s.stock_code = ?
                """, Long.class, code);
        return previous == null ? 0 : previous;
    }

    static String priceDriversReason(double changePercent, double newsImpact, long userNet,
                                      long botNet, long openNet) {
        if (newsImpact > .2) return "최근 뉴스 지표는 긍정적입니다. 가격은 실제 주문 체결로 결정됩니다.";
        if (newsImpact < -.2) return "최근 뉴스 지표는 부정적입니다. 가격은 실제 주문 체결로 결정됩니다.";
        if (openNet > 0) return "현재 미체결 매수 수량이 더 많습니다. 이후 체결 방향을 보장하지 않습니다.";
        if (openNet < 0) return "현재 미체결 매도 수량이 더 많습니다. 이후 체결 방향을 보장하지 않습니다.";
        return "현재 뉴스와 미체결 주문에서 뚜렷한 한 방향이 확인되지 않습니다.";
    }

    public List<DailyCandle> dailySummaries(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (market.findStock(normalized) == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        market.seedDailySummaries();
        return jdbc.query("""
                SELECT s.stock_code, d.trading_date, d.open_price, d.close_price, d.total_volume
                FROM daily_market_summaries d JOIN stocks s ON s.id = d.stock_id
                WHERE s.stock_code = ?
                ORDER BY d.trading_date DESC
                LIMIT 90
                """, (rs, row) -> new DailyCandle(rs.getString("stock_code"),
                rs.getDate("trading_date").toLocalDate().toString(), rs.getLong("open_price"),
                rs.getLong("close_price"), rs.getLong("total_volume")), normalized);
    }

    public OrderBook orderBook(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (market.findStock(normalized) == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        long id = market.stockId(normalized);
        List<OrderBookLevel> bids = jdbc.query("""
                SELECT price, SUM(remaining_quantity) quantity, COUNT(*) order_count FROM orders
                WHERE stock_id = ? AND side = 'BUY' AND status = 'OPEN'
                  AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP(3)) GROUP BY price ORDER BY price DESC LIMIT 10
                """, (rs, row) -> new OrderBookLevel(rs.getLong("price"), rs.getInt("quantity"), rs.getInt("order_count")), id);
        List<OrderBookLevel> asks = jdbc.query("""
                SELECT price, SUM(remaining_quantity) quantity, COUNT(*) order_count FROM orders
                WHERE stock_id = ? AND side = 'SELL' AND status = 'OPEN'
                  AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP(3)) GROUP BY price ORDER BY price ASC LIMIT 10
                """, (rs, row) -> new OrderBookLevel(rs.getLong("price"), rs.getInt("quantity"), rs.getInt("order_count")), id);
        return new OrderBook(normalized, bids, asks);
    }

    public Map<String,Object> symbolSnapshot(String code){
        var stock=market.findStock(code);
        if(stock==null)throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        var trades=publicTrades(code);
        return Map.of("symbol",code,"stock",stock,"orderbook",orderBook(code),"trades",trades,
            "chart",trades.stream().map(t->new PricePoint(t.price(),t.createdAt())).toList());
    }

    private record VolumeBreakdown(long userBuy, long userSell, long botBuy, long botSell) { }
}
