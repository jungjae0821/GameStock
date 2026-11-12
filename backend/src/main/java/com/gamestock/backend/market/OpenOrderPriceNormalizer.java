package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static com.gamestock.backend.market.PriceLimitPolicy.*;

/**
 * Open limit-order price normalization split out of {@link MarketService}: after a
 * tick-size or daily-band change, resting orders are rounded onto the new grid, and
 * orders outside the band are cancelled with their reserved cash refunded. Not a Spring
 * bean and keeps no state. Each changed order commits in its own short transaction so a
 * restart never applies the same cash difference twice.
 */
final class OpenOrderPriceNormalizer {
    private final MarketService market;
    private final JdbcTemplate jdbc;

    OpenOrderPriceNormalizer(MarketService market, JdbcTemplate jdbc) {
        this.market = market;
        this.jdbc = jdbc;
    }

    /** 기존 호가도 새 호가 단위 규칙을 따르도록 보정한다. 예약 현금 차액은 함께 정산한다. */
    void normalize() {
        List<OpenLimitOrder> orders = jdbc.query("""
                SELECT o.id, o.user_id, s.stock_code, o.side, o.price, o.remaining_quantity,
                       COALESCE(o.reserved_cash, 0), (u.password_hash = 'BOT') AS is_bot
                FROM orders o
                JOIN stocks s ON s.id = o.stock_id
                JOIN users u ON u.id = o.user_id
                WHERE o.status = 'OPEN' AND o.order_type = 'LIMIT' AND o.price IS NOT NULL
                """, (rs, row) -> new OpenLimitOrder(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getLong(5), rs.getInt(6), rs.getLong(7), rs.getBoolean(8)));
        for (OpenLimitOrder order : orders) {
            PriceBand allowedBand = order.bot() ? market.botPriceBand(order.stockCode()) : market.dailyPriceBand(order.stockCode());
            if (!allowedBand.contains(order.price())) {
                market.startupTransaction().executeWithoutResult(status -> cancelOutOfBandOrder(order));
                continue;
            }
            long rounded = "BUY".equals(order.side()) ? floorToTick(order.price()) : ceilToTick(order.price());
            long normalized = allowedBand.clamp(rounded);
            Long required = null;
            if ("BUY".equals(order.side())) {
                try {
                    long normalizedGross = Math.multiplyExact(normalized, order.remainingQuantity());
                    required = normalizedGross + MarketService.feeFor(normalizedGross);
                } catch (ArithmeticException error) {
                    // required stays null: the order is cancelled and refunded below.
                }
                if (required != null && normalized == order.price() && order.reservedCash() == required) continue;
            } else if (normalized == order.price()) continue;
            // A cash adjustment and its order update must commit together, or a restart
            // would apply the same difference again. Unchanged orders skip the transaction.
            Long reserved = required;
            market.startupTransaction().executeWithoutResult(status -> applyNormalizedPrice(order, normalized, reserved));
        }
    }

    private void cancelOutOfBandOrder(OpenLimitOrder order) {
        int cancelled = jdbc.update("""
                UPDATE orders
                SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0
                WHERE id = ? AND status = 'OPEN'
                """, order.id());
        if (cancelled > 0 && order.reservedCash() > 0) {
            jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
        }
    }

    /** {@code required} is the new buy reservation, or null when it overflowed. */
    private void applyNormalizedPrice(OpenLimitOrder order, long normalized, Long required) {
        if (!"BUY".equals(order.side())) {
            jdbc.update("UPDATE orders SET price = ? WHERE id = ?", normalized, order.id());
            return;
        }
        if (required == null) {
            if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
            jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", order.id());
            return;
        }
        long difference = order.reservedCash() - required;
        if (difference < 0 && jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ? AND cash >= ?", -difference, order.userId(), -difference) == 0) {
            if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
            jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", order.id());
            return;
        }
        if (difference > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", difference, order.userId());
        jdbc.update("UPDATE orders SET price = ?, reserved_cash = ? WHERE id = ?", normalized, required, order.id());
    }

    private record OpenLimitOrder(long id, long userId, String stockCode, String side, long price,
                                  int remainingQuantity, long reservedCash, boolean bot) { }
}
