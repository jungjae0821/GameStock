package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.gamestock.backend.market.DerivativeModels.*;
import static com.gamestock.backend.market.DerivativePositionStore.PositionRow;
import static com.gamestock.backend.market.DerivativePositionStore.instant;

/**
 * 파생 계좌 조회와 포지션 평가 표시. {@link DerivativeService}에서 분리했다.
 *
 * <p>Spring 빈이 아니며 상태를 갖지 않는다. 트랜잭션은 호출하는 서비스의
 * {@code @Transactional} 진입점(account·open·close)이 연다. 읽기만 하며 만기 픽싱을
 * 새로 만들지 않는다.
 */
final class DerivativeAccountView {
    private final JdbcTemplate jdbc;
    private final Supplier<Clock> clock;
    private final DerivativeIndexHistory indexHistory;
    private final DerivativeQuotes quotes;
    private final DerivativePositionStore store;

    DerivativeAccountView(JdbcTemplate jdbc, Supplier<Clock> clock, DerivativeIndexHistory indexHistory,
                          DerivativeQuotes quotes, DerivativePositionStore store) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.indexHistory = indexHistory;
        this.quotes = quotes;
        this.store = store;
    }

    Account account(long userId) {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        List<PositionRow> rows = store.positionRows(userId);
        Instant now = clock.get().instant();
        DerivativeBaskets.Values prices = quotes.loadPrices();
        double index = prices.index();
        Map<String, Double> navs = quotes.etfNavs(prices, index);
        double volatility = quotes.currentVolatility();
        List<Position> positions = new ArrayList<>(rows.size());
        long positionValue = 0;
        long unrealized = 0;
        for (PositionRow row : rows) {
            Position position = toPosition(row, index, navs, volatility, now);
            positions.add(position);
            positionValue += position.value();
            unrealized += position.unrealizedPnl();
        }
        long realized = jdbc.queryForObject(
                "SELECT COALESCE(SUM(realized_pnl), 0) FROM derivative_positions WHERE user_id = ?", Long.class, userId);
        long fees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(fee), 0) FROM derivative_trades WHERE user_id = ?", Long.class, userId);
        List<Trade> trades = jdbc.query("""
                SELECT id, position_id, action, kind, symbol, side, quantity, price, cash_flow, fee, created_at
                FROM derivative_trades WHERE user_id = ? ORDER BY id DESC LIMIT 20
                """, (rs, row) -> new Trade(rs.getLong("id"), rs.getLong("position_id"), rs.getString("action"),
                rs.getString("kind"), rs.getString("symbol"), rs.getString("side"), rs.getInt("quantity"),
                rs.getDouble("price"), rs.getLong("cash_flow"), rs.getLong("fee"),
                instant(rs.getTimestamp("created_at"))), userId);
        return new Account(cash, positionValue, cash + positionValue, unrealized, realized, fees, positions, trades);
    }

    private Position toPosition(PositionRow row, double index, Map<String, Double> navs,
                                double volatility, Instant now) {
        double mark;
        long value;
        switch (row.kind()) {
            case "ETF" -> {
                mark = navs.getOrDefault(row.symbol(), row.entryPrice());
                value = Math.round(mark * row.quantity());
            }
            case "FUTURE" -> {
                mark = index;
                long pnl = Math.round((mark - row.entryPrice()) * DerivativePricing.CONTRACT_MULTIPLIER
                        * row.quantity() * ("SHORT".equals(row.side()) ? -1 : 1));
                value = Math.max(0, row.margin() + pnl);
            }
            case "OPTION" -> {
                boolean call = "CALL".equals(row.optionType());
                Instant expiry = row.expiryAt() == null ? now : row.expiryAt().toInstant();
                boolean expired = !expiry.isAfter(now);
                if (expired) {
                    var fixing = row.expiryAt() == null ? java.util.OptionalDouble.empty() : indexHistory.existingFixing(expiry);
                    // A pending expiry retains its last stored valuation. Account reads cannot
                    // invent a fixing or roll back an otherwise valid trade in another product.
                    mark = fixing.isPresent() ? DerivativePricing.intrinsic(fixing.getAsDouble(), row.strike(), call) : row.markPrice();
                    value = fixing.isPresent() ? Math.round(mark * DerivativePricing.CONTRACT_MULTIPLIER) * row.quantity() : row.markValue();
                } else {
                    mark = DerivativePricing.optionPrice(index, row.strike(),
                            DerivativePricing.yearsLeft(expiry, now), volatility, call);
                    value = DerivativePricing.premiumWon(mark) * row.quantity();
                }
            }
            default -> {
                mark = row.entryPrice();
                value = row.entryNotional();
            }
        }
        long basis = "FUTURE".equals(row.kind()) ? row.margin() : row.entryNotional();
        long unrealized = value - basis;
        double unrealizedPercent = basis <= 0 ? 0 : Math.round(unrealized * 10_000.0 / basis) / 100.0;
        return new Position(row.id(), row.kind(), row.symbol(), row.name(), row.side(), row.quantity(),
                row.entryPrice(), mark, row.entryNotional(), row.margin(), row.leverage(),
                row.optionType(), row.strike(), row.expiryAt() == null ? null : row.expiryAt().toInstant().toString(),
                value, unrealized, unrealizedPercent, row.status(), row.openedAt());
    }
}
