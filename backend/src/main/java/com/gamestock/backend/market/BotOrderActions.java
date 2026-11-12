package com.gamestock.backend.market;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;

import static com.gamestock.backend.market.MarketModels.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

/**
 * One-at-a-time bot orders split out of {@link MarketService}: the liquidity provider
 * re-quoting a symbol, and a participant bot executing the intent it selected. MarketService
 * keeps the transactional, synchronized entry points and the per-action preparation (market
 * lock, simulation tick, random seed), then passes itself in on every call so that a test
 * spy or subclass of MarketService still sees {@code order} and {@code selectBotObservation}.
 */
final class BotOrderActions {
    private final MarketMakerEngine marketMaker = new MarketMakerEngine();
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final LiquidityProviderAccounts liquidity;
    private final MarketNewsFeed newsFeed;
    private final TradingProtectionService protection;

    BotOrderActions(JdbcTemplate jdbc, ApplicationEventPublisher events, LiquidityProviderAccounts liquidity,
                    MarketNewsFeed newsFeed, TradingProtectionService protection) {
        this.jdbc = jdbc;
        this.events = events;
        this.liquidity = liquidity;
        this.newsFeed = newsFeed;
        this.protection = protection;
    }

    /** One task per symbol refreshes both sides atomically; all fills still use the existing ledger. */
    void liquidityAction(MarketService market, String code) {
        if(market.adminRuntime()!=null&&market.adminRuntime().botsPaused())return;
        if (!protection.marketStatus().open()) return;
        TradingRestriction restriction = protection.restriction(code);
        if (restriction != null && !restriction.limitOrdersAllowed()) return;
        long lp = liquidity.ensureProvider();
        // Measure live depth before withdrawing our quotes; replacement itself is not a liquidity crisis.
        PriceMetricService.Metrics metrics = market.metrics(code, 0);
        market.cancelBotOrders(lp, market.stockId(code));
        liquidity.ensureInventory(code, lp);
        OrderBook externalBook=market.orderBook(code);
        double news=newsFeed.recentNewsBias(code).rate();
        long reference=market.dailyReferencePrice(code);
        PriceBand band=quoteBand(code,reference,news);
        MarketMakerEngine.QuoteConstraints constraints=new MarketMakerEngine.QuoteConstraints(band.lowerPrice(),band.upperPrice(),
                externalBook.bids().isEmpty()?0:externalBook.bids().get(0).price(),
                externalBook.asks().isEmpty()?0:externalBook.asks().get(0).price());
        MarketMakerEngine.RiskBook risk = market.liquidityRiskBook(code, lp);
        double fundamental=jdbc.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?",Double.class,market.stockId(code));
        fundamental=StockMarketProfile.valuationAnchor(code,reference,fundamental,news);
        double nearbyRange=Math.max(metrics.lastPrice()*.01,tickSize(Math.round(metrics.lastPrice()))*4.0);
        long externalBids=externalBook.bids().stream().filter(q->q.price()>=metrics.lastPrice()-nearbyRange).mapToLong(OrderBookLevel::quantity).sum();
        long externalAsks=externalBook.asks().stream().filter(q->q.price()<=metrics.lastPrice()+nearbyRange).mapToLong(OrderBookLevel::quantity).sum();
        double pressure=(externalBids-externalAsks)/(double)Math.max(1,externalBids+externalAsks);
        for (MarketMakerEngine.Quote quote : marketMaker.quotes(metrics, market.environment(code), risk,
                tickSize(Math.round(metrics.midPrice())), market.marketScale(code),constraints,fundamental,pressure)) {
            insertLiquidityQuote(market, code, lp, quote);
        }
        market.matchOrders(code);
        market.refreshMarkPrice(code);
        events.publishEvent(new MarketChangedEvent());
    }

    void participantAction(MarketService market, String username) {
        if(market.adminRuntime()!=null) {
            double share=market.adminRuntime().settings().traderShare();
            if(share<=0 || share<1 && java.util.concurrent.ThreadLocalRandom.current().nextDouble()>=share)return;
        }
        if (!protection.marketStatus().open()) return;
        BotProfile profile = market.traderProfiles().stream().filter(p -> p.username().equals(username)).findFirst().orElseThrow();
        List<String> codes = market.botStockCodes();
        if (codes.isEmpty()) return;
        long userId = traderBotId(username);
        MarketService.BotObservation observation = market.selectBotObservation(profile,userId,codes);
        String code = observation.code();
        if(market.adminRuntime()!=null&&market.adminRuntime().restriction(code)!=null)return;
        PriceMetricService.Metrics metrics = observation.metrics();
        long id = market.stockId(code);
        // Execute the same funded intent that won selection; do not redraw its signal after choosing a symbol.
        BotStrategyEngine.Decision decision = observation.decision();
        List<BotOrderPolicy.RestingOrder> resting=observation.resting();
        if (!resting.isEmpty()) {
            // An unrelated symbol or a fresh random pricing draw must not reset an order's queue priority/TTL.
            if (!observation.replace()) return;
            market.cancelBotOrders(userId,id);
            events.publishEvent(new MarketChangedEvent());
        }
        if (decision.quantity() <= 0) return;
        long price = "BUY".equals(decision.side()) ? floorToTick(Math.round(decision.limitPrice()))
                : ceilToTick(Math.round(decision.limitPrice()));
        PriceBand band = quoteBand(code,market.dailyReferencePrice(code),observation.news());
        price = "BUY".equals(decision.side()) ? floorToTick(band.clamp(price)) : ceilToTick(band.clamp(price));
        int quantity = Math.min(2,"SELL".equals(decision.side()) ? Math.min(decision.quantity(), market.availableQuantity(userId,id)) : decision.quantity());
        if (quantity <= 0) return;
        try {
            market.order(new OrderRequest(code, decision.side(), quantity, decision.market() ? "MARKET" : "LIMIT",
                    decision.market() ? null : price), userId);
            if (!decision.market()) jdbc.update("UPDATE orders SET expires_at=? WHERE user_id=? AND stock_id=? AND status='OPEN'",
                    Timestamp.from(market.clock().instant().plusMillis(Math.min(30_000,decision.ttlMillis()))), userId, id);
        } catch (IllegalArgumentException unavailable) {
            // Insufficient resources, protection, or user-style rate limits mean HOLD.
        }
    }

    private void insertLiquidityQuote(MarketService market, String code,long lp,MarketMakerEngine.Quote quote) {
        boolean buy = "BUY".equals(quote.side());
        PriceBand band = market.botPriceBand(code);
        long price = buy ? floorToTick(Math.round(quote.price())) : ceilToTick(Math.round(quote.price()));
        if (!band.contains(price)) return;
        OrderBook book = market.orderBook(code);
        if (buy && !book.asks().isEmpty() && price >= book.asks().get(0).price()
                || !buy && !book.bids().isEmpty() && price <= book.bids().get(0).price()) return;
        long id = market.stockId(code);
        MarketMakerEngine.RiskBook risk = market.liquidityRiskBook(code,lp);
        int quantity = buy ? Math.min(quote.quantity(), (int)Math.min(Integer.MAX_VALUE,
                risk.cashBudget()/Math.max(1,price*1.001))) : Math.min(quote.quantity(),market.availableQuantity(lp,id));
        if (quantity<=0) return;
        long reserved = buy ? price*quantity+MarketService.feeFor(price*quantity) : 0;
        if (buy && jdbc.update("UPDATE users SET cash=cash-? WHERE id=? AND cash>=?",reserved,lp,reserved)==0) return;
        jdbc.update("""
                INSERT INTO orders (user_id,stock_id,side,order_type,price,quantity,remaining_quantity,reserved_cash,reserved_quantity,status,expires_at,compact_origin)
                VALUES (?,?,?,'LIMIT',?,?,?,?,?,'OPEN',?,?)
                """,lp,id,quote.side(),price,quantity,quantity,reserved,buy?0:quantity,
                Timestamp.from(market.clock().instant().plusMillis(4000)),market.compactLedger());
    }

    private long traderBotId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ? AND password_hash = ?",
                Long.class, username, MarketService.TRADER_BOT_PASSWORD);
    }
}
