package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * The liquidity provider's account split out of {@link MarketService}: its user row,
 * finite share inventory, one-time cash reserve and the per-symbol cash budget that
 * quotes may spend. Not a Spring bean: MarketService constructs it, places and cancels
 * the provider's quotes itself and keeps every public entry point, so the
 * {@code synchronized} monitor and transactions still wrap these calls.
 */
final class LiquidityProviderAccounts {
    static final String USERNAME = "liquidity_provider";
    static final int INITIAL_INVENTORY = 100;
    private static final long STARTING_CASH = 50_000_000L;
    private static final long ONE_TIME_RESERVE = 50_000_000L;
    private static final int RESERVE_VERSION = 1;

    private final MarketService market;
    private final JdbcTemplate jdbc;

    LiquidityProviderAccounts(MarketService market, JdbcTemplate jdbc) {
        this.market = market;
        this.jdbc = jdbc;
    }

    long ensureProvider() {
        jdbc.update("INSERT IGNORE INTO users (username, password_hash, nickname, cash) VALUES (?, 'BOT', ?, ?)",
                USERNAME, "유동성 공급자", STARTING_CASH);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, USERNAME);
    }

    /**
     * Seed a new LP lot and top up a nearly depleted lot within the finite supply bound.
     *
     * A few remaining shares must not block an otherwise eligible refill.
     */
    void ensureInventory(String code, long userId) {
        long id = market.stockId(code);
        int initialInventory = StockMarketProfile.initialLiquidityInventory(code,INITIAL_INVENTORY);
        List<MarketService.HoldingState> holdings = jdbc.query("""
                SELECT quantity, settled_quantity, average_price, realized_profit_loss
                FROM portfolios WHERE user_id = ? AND stock_id = ? FOR UPDATE
                """, (rs, row) -> new MarketService.HoldingState(
                rs.getInt("quantity"),
                rs.getInt("settled_quantity"),
                rs.getLong("average_price"),
                rs.getLong("realized_profit_loss")), userId, id);
        if (holdings.isEmpty()) {
            jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                    VALUES (?, ?, ?, ?, ?, 0)
                    """, userId, id, initialInventory, initialInventory, market.findStock(code).price());
            recordIssuance(id, initialInventory);
        } else if (holdings.get(0).quantity() <= 4) {
            // The startup/legacy path obeys the same finite supply bound as inline quotes.
            long circulating=jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM portfolios WHERE stock_id=?",Long.class,id);
            long nearbyAsks=jdbc.queryForObject("SELECT COALESCE(SUM(remaining_quantity),0) FROM orders WHERE stock_id=? AND user_id<>? AND side='SELL' AND status='OPEN' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AND price<=?",Long.class,id,userId,market.findStock(code).price()*1.02);
            int refill=(int)Math.min(32,Math.max(0,Math.max(512L,initialInventory*4L)-circulating));
            if(refill==0 || nearbyAsks>=4)return;
            MarketService.HoldingState current=holdings.get(0);
            int nextQuantity=current.quantity()+refill;
            long average=Math.round((current.averagePrice()*(double)current.quantity()
                    +market.findStock(code).price()*(double)refill)/nextQuantity);
            jdbc.update("""
                    UPDATE portfolios
                    SET quantity = ?, settled_quantity = ?, average_price = ?
                    WHERE user_id = ? AND stock_id = ?
                    """, nextQuantity, current.settledQuantity()+refill, average, userId, id);
            recordIssuance(id, refill);
        }
    }

    private void recordIssuance(long stockId, int quantity) {
        jdbc.update("INSERT INTO market_inventory_issuance(stock_id,quantity) VALUES(?,?) ON DUPLICATE KEY UPDATE quantity=quantity+VALUES(quantity)", stockId, quantity);
    }

    /** Apply one finite simulation-only LP reserve and keep its per-symbol projection consistent. */
    void ensureCashReserve(long lp,List<String> codes) {
        jdbc.update("INSERT IGNORE INTO market_liquidity_state(id,reserve_version) VALUES (1,0)");
        if(jdbc.update("UPDATE market_liquidity_state SET reserve_version=? WHERE id=1 AND reserve_version=0",RESERVE_VERSION)!=1)return;
        jdbc.update("UPDATE users SET cash=cash+? WHERE id=?",ONE_TIME_RESERVE,lp);
        long share=ONE_TIME_RESERVE/Math.max(1,codes.size()),remainder=ONE_TIME_RESERVE%Math.max(1,codes.size());
        for(int i=0;i<codes.size();i++) {
            long delta=share+(i<remainder?1:0),id=market.stockId(codes.get(i));
            jdbc.update("UPDATE lp_risk_books SET opening_cash=opening_cash+? WHERE stock_id=?",delta,id);
            jdbc.update("UPDATE lp_cash_projection SET cash=cash+? WHERE stock_id=?",delta,id);
        }
    }

    MarketMakerEngine.RiskBook riskBook(String code,long lp) {
        long id = market.stockId(code);
        refreshProjection(id,lp);
        return jdbc.queryForObject("""
                SELECT c.cash + COALESCE((SELECT lp_cash_delta FROM bot_ledger_totals b WHERE b.stock_id=r.stock_id),0) - COALESCE((SELECT SUM(o.reserved_cash) FROM orders o
                    WHERE o.user_id=? AND o.stock_id=r.stock_id AND o.status='OPEN'),0) AS budget,
                    r.target_inventory,r.max_inventory,r.risk_limit
                FROM lp_risk_books r JOIN lp_cash_projection c ON c.stock_id=r.stock_id WHERE r.stock_id=?
                """, (rs,n)->new MarketMakerEngine.RiskBook(Math.max(0,Math.min(market.availableCash(lp),rs.getLong(1))),
                        market.holdingState(id,lp).quantity(),rs.getInt(2),rs.getInt(3),rs.getLong(4)),lp,id);
    }

    long allocatedCash(long id,long lp) {
        refreshProjection(id,lp);
        return jdbc.queryForObject("SELECT c.cash+COALESCE(b.lp_cash_delta,0) FROM lp_cash_projection c LEFT JOIN bot_ledger_totals b ON b.stock_id=c.stock_id WHERE c.stock_id=?",Long.class,id);
    }

    void refreshProjection(long id,long lp) {
        jdbc.update("INSERT IGNORE INTO lp_cash_projection SELECT stock_id,baseline_trade_id,opening_cash FROM lp_risk_books WHERE stock_id=?",id);
        long cursor=jdbc.queryForObject("SELECT last_id FROM lp_cash_projection WHERE stock_id=?",Long.class,id);
        long end=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM trades WHERE stock_id=?",Long.class,id);
        if(end>cursor) {
            long delta=jdbc.queryForObject("""
                    SELECT COALESCE(SUM(CASE WHEN t.seller_id=? THEN t.quantity*t.price-t.seller_fee ELSE 0 END
                        - CASE WHEN t.buyer_id=? THEN t.quantity*t.price+t.buyer_fee ELSE 0 END),0)
                    FROM trades t WHERE t.stock_id=? AND t.id>? AND t.id<=?
                      AND NOT EXISTS (SELECT 1 FROM settlements s WHERE s.trade_id=t.id AND s.status='CANCELLED')
                    """,Long.class,lp,lp,id,cursor,end);
            jdbc.update("UPDATE lp_cash_projection SET cash=cash+?,last_id=? WHERE stock_id=?",delta,end,id);
        }
    }
}
