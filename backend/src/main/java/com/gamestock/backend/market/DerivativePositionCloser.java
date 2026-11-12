package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.gamestock.backend.market.DerivativeModels.*;
import static com.gamestock.backend.market.DerivativePositionStore.PositionRow;
import static com.gamestock.backend.market.DerivativePositionStore.won;
import static com.gamestock.backend.market.DerivativeQuotes.basketCode;
import static com.gamestock.backend.market.DerivativeQuotes.etfDefinition;

/**
 * 사용자 청산 체결: 청산가 계산, 현금 정산, 포지션 부분·전량 종료, 거래 기록.
 * {@link DerivativeService}에서 분리했다.
 *
 * <p>Spring 빈이 아니며 상태를 갖지 않는다. 거래 가능 여부 확인, 사용자·포지션 행 잠금,
 * 만기 옵션 분기는 서비스의 {@code @Transactional} 진입점(open·close·closeForCollateral)이 먼저 처리하고,
 * 이 객체는 같은 트랜잭션·잠금 안에서만 호출된다.
 */
final class DerivativePositionCloser {
    private final JdbcTemplate jdbc;
    private final Supplier<Clock> clock;
    private final DerivativeIndexHistory indexHistory;
    private final DerivativeQuotes quotes;
    private final DerivativePositionStore store;
    private final DerivativeAccountView accounts;

    DerivativePositionCloser(JdbcTemplate jdbc, Supplier<Clock> clock, DerivativeIndexHistory indexHistory,
                             DerivativeQuotes quotes, DerivativePositionStore store, DerivativeAccountView accounts) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.indexHistory = indexHistory;
        this.quotes = quotes;
        this.store = store;
        this.accounts = accounts;
    }

    OrderResult closeEtfBySymbol(long userId, String symbol, int quantity,
                                 DerivativeBaskets.Values prices, double index) {
        DerivativeBaskets.Definition definition = etfDefinition(symbol);
        List<PositionRow> rows = store.openEtfRows(userId, definition.code());
        int remaining = quantity;
        OrderResult last = null;
        for (PositionRow row : rows) {
            if (remaining <= 0) break;
            int take = Math.min(remaining, row.quantity());
            last = closeInternal(row, take, "CLOSE");
            remaining -= take;
        }
        if (remaining > 0)
            throw new IllegalArgumentException(definition.name() + " 보유 수량이 부족합니다.");
        if (last == null) throw new IllegalArgumentException("보유한 ETF가 없습니다.");
        return last;
    }

    OrderResult closeInternal(PositionRow row, int quantity, String action) {
        Instant now = clock.get().instant();
        DerivativeBaskets.Values prices = quotes.loadPrices();
        quotes.requireFresh(prices, basketCode(row.kind(), row.symbol()));
        prices = prices.execution("SHORT".equals(row.side()) || "PUT".equals(row.optionType()));
        double index = prices.index();
        Map<String, Double> navs = quotes.etfNavs(prices, index);
        long positionPortion = portion(row.entryNotional(), quantity, row.quantity());
        long marginPortion = portion(row.margin(), quantity, row.quantity());
        double price;
        long proceeds;
        long fee;
        long realized;
        long cashFlow;
        switch (row.kind()) {
            case "ETF" -> {
                price = navs.getOrDefault(row.symbol(), row.entryPrice());
                proceeds = Math.round(price * quantity);
                fee = DerivativePricing.fee(proceeds, DerivativePricing.ETF_FEE_RATE);
                realized = proceeds - positionPortion - fee;
                cashFlow = proceeds - fee;
            }
            case "FUTURE" -> {
                price = index;
                int direction = "SHORT".equals(row.side()) ? -1 : 1;
                long pnl = Math.round((price - row.entryPrice()) * DerivativePricing.CONTRACT_MULTIPLIER
                        * quantity * direction);
                long equity = Math.max(0, marginPortion + pnl);
                fee = Math.min(equity, DerivativePricing.fee(
                        Math.round(price * DerivativePricing.CONTRACT_MULTIPLIER * quantity),
                        DerivativePricing.FUTURE_FEE_RATE));
                cashFlow = equity - fee;
                realized = cashFlow - marginPortion;
                proceeds = equity;
            }
            case "OPTION" -> {
                boolean call = "CALL".equals(row.optionType());
                Timestamp expiryAt = row.expiryAt();
                if (expiryAt == null) throw new IllegalStateException("옵션 만기 정보가 없어 정산을 보류합니다.");
                boolean expired = !expiryAt.toInstant().isAfter(now);
                double points = expired
                        ? DerivativePricing.intrinsic(indexHistory.fixing(expiryAt.toInstant()), row.strike(), call)
                        : DerivativePricing.optionPrice(index, row.strike(),
                        DerivativePricing.yearsLeft(expiryAt.toInstant(), now), quotes.currentVolatility(), call);
                price = points;
                proceeds = (expired
                        ? Math.round(points * DerivativePricing.CONTRACT_MULTIPLIER)
                        : DerivativePricing.premiumWon(points)) * quantity;
                fee = expired ? 0 : DerivativePricing.fee(proceeds, DerivativePricing.OPTION_FEE_RATE);
                realized = proceeds - positionPortion - fee;
                cashFlow = proceeds - fee;
            }
            default -> throw new IllegalArgumentException("지원하지 않는 포지션 종류입니다.");
        }
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", cashFlow, row.userId());
        int remaining = row.quantity() - quantity;
        if (remaining <= 0) {
            jdbc.update("""
                    UPDATE derivative_positions
                    SET quantity = 0, entry_notional = 0, margin = 0, mark_price = ?, mark_value = 0,
                        realized_pnl = realized_pnl + ?, status = ?, closed_at = ?, close_reason = ?
                    WHERE id = ?
                    """, price, realized, action, Timestamp.from(now), action, row.id());
        } else {
            long remainingValue;
            if ("FUTURE".equals(row.kind())) {
                remainingValue = Math.max(0, row.margin() - marginPortion)
                        + Math.round((price - row.entryPrice()) * DerivativePricing.CONTRACT_MULTIPLIER
                        * remaining * ("SHORT".equals(row.side()) ? -1 : 1));
            } else if ("OPTION".equals(row.kind())) {
                boolean expired = row.expiryAt() == null || !row.expiryAt().toInstant().isAfter(now);
                remainingValue = (expired ? Math.round(price * DerivativePricing.CONTRACT_MULTIPLIER)
                        : DerivativePricing.premiumWon(price)) * remaining;
            } else {
                remainingValue = Math.round(price * remaining);
            }
            jdbc.update("""
                    UPDATE derivative_positions
                    SET quantity = ?, entry_notional = entry_notional - ?, margin = margin - ?,
                        mark_price = ?, mark_value = ?, mark_version = GREATEST(mark_version, ?), realized_pnl = realized_pnl + ?
                    WHERE id = ?
                    """, remaining, positionPortion, marginPortion, price, remainingValue, prices.version(), realized, row.id());
        }
        store.recordTrade(row.userId(), row.id(), action, row.kind(), row.symbol(), row.side(), quantity,
                price, cashFlow, fee);
        return new OrderResult(row.name() + " " + quantity + "계약 청산 · 정산 " + won(cashFlow), accounts.account(row.userId()));
    }

    private static long portion(long total, int quantity, int originalQuantity) {
        if (originalQuantity <= 0) return 0;
        if (quantity >= originalQuantity) return total;
        return Math.round(total * (double) quantity / originalQuantity);
    }
}
