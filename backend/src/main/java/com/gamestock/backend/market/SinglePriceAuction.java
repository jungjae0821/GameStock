package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static com.gamestock.backend.market.PriceLimitPolicy.*;

/**
 * Single-price auction split out of {@link MarketService}: choosing the clearing price,
 * crossing resting limit orders at that price, and settling each fill into cash, holdings,
 * the trade ledger and the orders themselves. Not a Spring bean and keeps no state; the
 * caller already holds the market monitor, the market lock and the transaction.
 */
final class SinglePriceAuction {
    private final MarketService market;
    private final JdbcTemplate jdbc;
    private final HoldingLedger holdings;
    private final TradingProtectionService protection;

    SinglePriceAuction(MarketService market, JdbcTemplate jdbc, HoldingLedger holdings,
                       TradingProtectionService protection) {
        this.market = market;
        this.jdbc = jdbc;
        this.holdings = holdings;
        this.protection = protection;
    }

    /** Maximise executable volume, then minimise imbalance and distance from the quote. */
    void close(String code) {
        AdminRuntimeControl adminRuntime = market.adminRuntime();
        if(adminRuntime!=null&&adminRuntime.restriction(code)!=null)return;
        long stockId = market.stockId(code);
        long reference = market.findStock(code).price();
        PriceBand daily = market.dailyPriceBand(code);
        PriceBand bots = market.botPriceBand(code);
        List<MatchRow> orders = jdbc.query("""
                SELECT o.id, o.user_id, o.price, o.quantity, o.remaining_quantity, o.order_type,
                       o.created_at, o.reserved_cash, o.reserved_quantity, o.side,
                       (u.password_hash = 'BOT') AS is_bot
                FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.stock_id = ? AND o.status = 'OPEN' AND o.order_type = 'LIMIT'
                  AND o.remaining_quantity > 0 ORDER BY o.created_at, o.id
                """, this::auctionRow, stockId);
        java.util.SortedSet<Long> candidates = new java.util.TreeSet<>();
        candidates.add(floorToTick(reference));
        for (MatchRow order : orders) {
            if (daily.contains(order.price()) && order.price() % tickSize(order.price()) == 0) candidates.add(order.price());
        }
        long price = reference;
        long bestVolume = 0;
        long bestImbalance = Long.MAX_VALUE;
        for (long candidate : candidates) {
            if (!daily.contains(candidate)) continue;
            long buys = 0;
            long sells = 0;
            for (MatchRow order : orders) {
                if (order.bot() && !bots.contains(candidate)) continue;
                if ("BUY".equals(order.side()) && order.price() >= candidate) buys += order.remainingQuantity();
                if ("SELL".equals(order.side()) && order.price() <= candidate) sells += order.remainingQuantity();
            }
            long volume = Math.min(buys, sells);
            long imbalance = Math.abs(buys - sells);
            if (volume > bestVolume || (volume == bestVolume && volume > 0 &&
                    (imbalance < bestImbalance || (imbalance == bestImbalance
                            && Math.abs(candidate - reference) < Math.abs(price - reference))))) {
                price = candidate;
                bestVolume = volume;
                bestImbalance = imbalance;
            }
        }
        if (bestVolume == 0) return;
        long volume = 0;
        while (true) {
            MatchRow buy = topAuctionOrder(stockId, "BUY", price, bots.contains(price));
            MatchRow sell = topAuctionOrder(stockId, "SELL", price, bots.contains(price));
            if (buy == null || sell == null) break;
            if (buy.userId() == sell.userId()) {
                MatchRow alternativeSell = topAuctionOrder(stockId, "SELL", price, bots.contains(price), buy.userId());
                if (alternativeSell == null) break;
                sell = alternativeSell;
            }
            int quantity = Math.min(buy.remainingQuantity(), sell.remainingQuantity());
            if (!canSettle(buy, sell, quantity, price, stockId)) {
                if (!canPayForFill(buy,quantity,price)) market.cancelOrderInternal(buy.id());
                else market.cancelOrderInternal(sell.id());
                continue;
            }
            MatchRow maker = earlier(buy, sell) ? buy : sell;
            MatchRow taker = maker.id() == buy.id() ? sell : buy;
            settleTrade(stockId, buy, sell, maker, taker, quantity, price);
            volume += quantity;
        }
        if (volume > 0) market.moveToPrice(code, price, volume);
    }

    private MatchRow topAuctionOrder(long stockId, String side, long price, boolean botsAllowed) {
        return topAuctionOrder(stockId, side, price, botsAllowed, 0L);
    }

    private MatchRow topAuctionOrder(long stockId, String side, long price, boolean botsAllowed,
                                     long excludedUserId) {
        String comparison = "BUY".equals(side) ? ">=" : "<=";
        String priority = "BUY".equals(side) ? "DESC" : "ASC";
        String excluded = excludedUserId > 0 ? " AND o.user_id <> ? " : "";
        List<MatchRow> rows = jdbc.query("""
                SELECT o.id, o.user_id, o.price, o.quantity, o.remaining_quantity, o.order_type,
                       o.created_at, o.reserved_cash, o.reserved_quantity, o.side,
                       (u.password_hash = 'BOT') AS is_bot
                FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.stock_id = ? AND o.side = ? AND o.status = 'OPEN'
                  AND o.order_type = 'LIMIT' AND o.remaining_quantity > 0 AND o.price
                """ + comparison + " ? " + (botsAllowed ? "" : "AND u.password_hash <> 'BOT' ")
                + excluded
                + "ORDER BY o.price " + priority + ", o.created_at, o.id LIMIT 1",
                this::auctionRow, excludedUserId > 0
                        ? new Object[]{stockId, side, price, excludedUserId}
                        : new Object[]{stockId, side, price});
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MatchRow auctionRow(ResultSet rs, int row) throws SQLException {
        return new MatchRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6), market.databaseInstant(rs.getTimestamp(7)), rs.getLong(8), rs.getInt(9), rs.getString(10), rs.getBoolean(11));
    }

    private boolean canSettle(MatchRow buy, MatchRow sell, int quantity, long tradePrice, long stockId) {
        if (!canPayForFill(buy,quantity,tradePrice)) return false;
        return sell.reservedQuantity() > 0 || market.availableQuantity(sell.userId(), stockId) >= quantity;
    }

    private long releasedReservation(MatchRow buy,int quantity) {
        if (buy.reservedCash()==0) return 0;
        long remainingGross=buy.price()*(buy.remainingQuantity()-(long)quantity);
        return buy.reservedCash()-remainingGross-MarketService.feeFor(remainingGross);
    }

    private boolean canPayForFill(MatchRow buy,int quantity,long price) {
        long gross=price*quantity;
        return market.availableCash(buy.userId())+releasedReservation(buy,quantity)>=gross+MarketService.feeFor(gross);
    }

    private void settleTrade(long stockId, MatchRow buy, MatchRow sell, MatchRow maker, MatchRow taker, int quantity, long tradePrice) {
        long amount = tradePrice * quantity;
        boolean internal=jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id IN (?,?) AND password_hash IN ('BOT','TRADER')",Integer.class,buy.userId(),sell.userId())==2;
        long buyerFee = internal?0:MarketService.feeFor(amount);
        long sellerFee = buyerFee;
        MarketService.HoldingState buyerBefore = holdings.state(stockId, buy.userId());
        MarketService.HoldingState sellerBefore = holdings.state(stockId, sell.userId());
        if (buy.reservedCash() > 0) {
            // Use the actual change in the remaining reservation: rounded per-fill fees are not additive.
            long reservedChunk = releasedReservation(buy,quantity);
            jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reservedChunk - amount - buyerFee, buy.userId());
        } else {
            jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ?", amount + buyerFee, buy.userId());
        }
        holdings.adjust(stockId, buy.userId(), quantity, tradePrice, buyerFee, true, buyerBefore);
        holdings.adjust(stockId, sell.userId(), quantity, tradePrice, sellerFee, false, sellerBefore);
        // GameStock settles fills immediately; the seller receives cash and
        // the buyer's shares become settled in the same transaction.
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", amount - sellerFee, sell.userId());
        jdbc.update("""
                INSERT INTO trades (stock_id, buy_order_id, sell_order_id, buyer_id, seller_id,
                                    maker_order_id, taker_order_id, aggressor_side, quantity, price,
                                    buyer_fee, seller_fee, fee)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stockId, buy.id(), sell.id(), buy.userId(), sell.userId(), maker.id(), taker.id(),
                taker.side(), quantity, tradePrice, buyerFee, sellerFee, buyerFee + sellerFee);
        long tradeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO settlements (trade_id, buyer_id, seller_id, stock_id, quantity, gross_amount,
                                         buyer_fee, seller_fee, settlement_at, status,
                                         buyer_quantity_before, buyer_settled_quantity_before,
                                         buyer_average_price_before, buyer_realized_profit_loss_before,
                                         seller_quantity_before, seller_settled_quantity_before,
                                         seller_average_price_before, seller_realized_profit_loss_before, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'SETTLED',
                        ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, tradeId, buy.userId(), sell.userId(), stockId, quantity, amount, buyerFee, sellerFee,
                buyerBefore.quantity(), buyerBefore.settledQuantity(), buyerBefore.averagePrice(),
                buyerBefore.realizedProfitLoss(), sellerBefore.quantity(), sellerBefore.settledQuantity(),
                sellerBefore.averagePrice(), sellerBefore.realizedProfitLoss());
        updateMatchedOrder(buy, quantity, tradePrice);
        updateMatchedOrder(sell, quantity, tradePrice);
        protection.recordTrade(stockId, tradePrice);
    }

    private void updateMatchedOrder(MatchRow order, int filledQuantity, long tradePrice) {
        int remaining = order.remainingQuantity() - filledQuantity;
        int filledBefore = order.quantity() - order.remainingQuantity();
        long nextPrice = "MARKET".equals(order.orderType())
                ? weightedAverage(order.price(), filledBefore, tradePrice, filledQuantity)
                : order.price();
        long reservedCash = order.reservedCash();
        if (reservedCash > 0 && "BUY".equals(order.side())) {
            // Recompute the reservation from the remaining quantity so fee
            // rounding does not accumulate a one-won drift over many fills.
            long remainingGross = order.price() * (long) remaining;
            reservedCash = remaining == 0 ? 0 : remainingGross + MarketService.feeFor(remainingGross);
        }
        int reservedQuantity = order.reservedQuantity();
        if (reservedQuantity > 0 && "SELL".equals(order.side())) reservedQuantity = Math.max(0, reservedQuantity - filledQuantity);
        jdbc.update("UPDATE orders SET price = ?, remaining_quantity = ?, reserved_cash = ?, reserved_quantity = ?, status = ? WHERE id = ?",
                nextPrice, remaining, reservedCash, reservedQuantity, remaining == 0 ? "FILLED" : "OPEN", order.id());
    }

    private long weightedAverage(long previousAverage, int filledBefore, long tradePrice, int filledQuantity) {
        int filled = filledBefore + filledQuantity;
        return filled == 0 ? 0 : Math.round(((double) previousAverage * filledBefore + (double) tradePrice * filledQuantity) / filled);
    }

    private boolean earlier(MatchRow first, MatchRow second) {
        int compared = first.createdAt().compareTo(second.createdAt());
        return compared < 0 || (compared == 0 && first.id() < second.id());
    }

    private record MatchRow(long id, long userId, long price, int quantity, int remainingQuantity, String orderType,
                            Instant createdAt, long reservedCash, int reservedQuantity, String side, boolean bot) { }
}
