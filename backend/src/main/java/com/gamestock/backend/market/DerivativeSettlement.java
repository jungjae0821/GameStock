package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.gamestock.backend.market.DerivativePositionStore.OpenRow;

/**
 * 평가 갱신, 옵션 만기 정산, 선물 강제청산.
 *
 * <p>DerivativeService가 생성하는 package-private 협력 객체다. 자체 모니터는 없다.
 * {@link #refresh()}는 DerivativeService.refreshMarks()의 {@code synchronized}(서비스 인스턴스 모니터)
 * 안에서만 호출되므로 인스턴스 안의 갱신 직렬화는 분리 전과 같다. {@link #expireOption(long)}은
 * 주문 경로(서비스의 {@code @Transactional} 메서드)에서도 호출되며, 원래도 모니터 없이 DB 행 잠금만 썼다.
 */
final class DerivativeSettlement {
    private static final Logger log = LoggerFactory.getLogger(DerivativeService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Supplier<Clock> clock;
    private final DerivativeIndexHistory indexHistory;
    private final DerivativePriceOracle oracle;
    private final DerivativeBaskets baskets;
    private final DerivativeQuotes quotes;
    private final DerivativePositionStore store;
    private final Supplier<DerivativeMarketImpact> marketImpact;

    DerivativeSettlement(JdbcTemplate jdbc, TransactionTemplate transactions, Supplier<Clock> clock,
                         DerivativeIndexHistory indexHistory, DerivativePriceOracle oracle, DerivativeBaskets baskets,
                         DerivativeQuotes quotes, DerivativePositionStore store,
                         Supplier<DerivativeMarketImpact> marketImpact) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.indexHistory = indexHistory;
        this.oracle = oracle;
        this.baskets = baskets;
        this.quotes = quotes;
        this.store = store;
        this.marketImpact = marketImpact;
    }

    void refresh() {
        Instant now = clock.get().instant();
        DerivativeBaskets.Values prices = transactions.execute(status -> {
            baskets.lock();
            indexHistory.initializeBases();
            oracle.refresh(now);
            baskets.rebalance(now);
            baskets.advanceValuationVersion();
            // Current reads keep a rejected oracle CAS from promoting its old MVCC snapshot
            // into a fresh index sample or position mark. Reference locks last through commit.
            DerivativeBaskets.Values observed = baskets.loadCurrent();
            // 차트용 표본은 매번 남기고, 모든 구성 종목이 신선할 때만 정산에 쓸 수 있다고 표시한다.
            double observedIndex = observed.index();
            if (Double.isFinite(observedIndex) && observedIndex > 0)
                indexHistory.observe(observedIndex, now, quotes.fresh(observed, "ETFIDX", now));
            return observed;
        });
        double index = prices.index();
        quotes.series("FUTIDX", index);
        Map<String, Double> navs = quotes.etfNavs(prices, index);
        for (DerivativeBaskets.Definition definition : DerivativeQuotes.ETFS) quotes.series("ETF:" + definition.code(),
                navs.getOrDefault(definition.code(), 1000.0));
        double volatility = quotes.currentVolatility();
        Long highWater = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM derivative_positions", Long.class);
        DerivativeMarketImpact.Accumulator impact = null;
        DerivativeMarketImpact marketImpact = this.marketImpact.get();
        if (marketImpact != null) {
            List<Double> prior = jdbc.queryForList("""
                    SELECT index_value FROM derivative_index_observations
                    WHERE observed_at BETWEEN ? AND ? AND settleable=TRUE AND invalidated=FALSE
                    ORDER BY observed_at DESC LIMIT 1
                    """, Double.class, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.minusSeconds(30)));
            impact = new DerivativeMarketImpact.Accumulator(index, prior.isEmpty() ? index : prior.get(0), volatility);
        }
        long cursor = 0;
        while (cursor < highWater) {
            List<OpenRow> rows = jdbc.query("""
                    SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                           leverage, option_type, strike, expiry_at, opened_at, realized_pnl
                    FROM derivative_positions WHERE status = 'OPEN' AND id>? AND id<=? ORDER BY id LIMIT 500
                    """, DerivativePositionStore.OPEN_ROW, cursor, highWater);
            if (rows.isEmpty()) break;
            cursor = rows.get(rows.size()-1).id();
            for (OpenRow row : rows) {
                if ("OPTION".equals(row.kind()) && row.expiryAt() != null && !row.expiryAt().toInstant().isAfter(now)) {
                    settleInTransaction(() -> expireOption(row.id()));
                    continue;
                }
                if (!quotes.fresh(prices, DerivativeQuotes.basketCode(row.kind(), row.symbol()), clock.get().instant())) continue;
                long value;
                double mark;
                switch (row.kind()) {
                    case "ETF" -> {
                        mark = navs.getOrDefault(row.symbol(), row.entryPrice());
                        value = Math.round(mark * row.quantity());
                    }
                    case "FUTURE" -> {
                        mark = index;
                        value = row.margin() + Math.round((mark - row.entryPrice())
                                * DerivativePricing.CONTRACT_MULTIPLIER * row.quantity()
                                * ("SHORT".equals(row.side()) ? -1 : 1));
                        if (value <= row.margin() * DerivativePricing.MAINTENANCE_RATE) {
                            settleInTransaction(() -> liquidate(row.userId(), row.id()));
                            continue;
                        }
                    }
                    case "OPTION" -> {
                        boolean call = "CALL".equals(row.optionType());
                        Instant expiry = row.expiryAt() == null ? now : row.expiryAt().toInstant();
                        if (!expiry.isAfter(now)) {
                            settleInTransaction(() -> expireOption(row.id()));
                            continue;
                        }
                        mark = DerivativePricing.optionPrice(index, row.strike(),
                                DerivativePricing.yearsLeft(expiry, now), volatility, call);
                        value = Math.max(10, Math.round(mark * DerivativePricing.CONTRACT_MULTIPLIER)) * row.quantity();
                    }
                    default -> {
                        continue;
                    }
                }
                // A delayed instance must not overwrite a newer mark, a position opened
                // after its price snapshot, or a partial close using that same snapshot.
                jdbc.update("""
                        UPDATE derivative_positions SET mark_price = ?, mark_value = ?, mark_version = ?
                        WHERE id = ? AND status = 'OPEN' AND quantity = ? AND mark_version < ?
                        """, mark, value, prices.version(), row.id(), row.quantity(), prices.version());
                if (impact != null) {
                    if ("FUTURE".equals(row.kind())) impact.future(row.side(), row.quantity(), row.entryPrice(), row.margin());
                    else if ("OPTION".equals(row.kind())) impact.option("CALL".equals(row.optionType()), row.quantity(),
                            row.strike(), DerivativePricing.yearsLeft(row.expiryAt().toInstant(), now));
                }
            }
        }
        if (impact != null) {
            // A bounded, indexed trailing window; older covering/forced selling fades out linearly.
            Instant flowAt = clock.get().instant();
            var accumulator = impact;
            jdbc.query("""
                    SELECT side, SUM(price * quantity * 1000 *
                      GREATEST(0, 1 - TIMESTAMPDIFF(MICROSECOND,created_at,?) / 30000000.0)) AS notional
                    FROM derivative_trades WHERE kind='FUTURE' AND action IN ('CLOSE','LIQUIDATE')
                      AND created_at BETWEEN ? AND ? GROUP BY side
                    """, rs -> { accumulator.closedFuture(rs.getString("side"), rs.getDouble("notional")); },
                    Timestamp.from(flowAt), Timestamp.from(flowAt.minusSeconds(30)), Timestamp.from(flowAt));
            var constituents = quotes.fresh(prices, "ETFIDX", flowAt)
                    ? prices.basket("ETFIDX").members().stream().map(DerivativeBaskets.Member::code)
                        .collect(java.util.stream.Collectors.toSet()) : java.util.Set.<String>of();
            marketImpact.publish(impact.snapshot(prices.version(), now, constituents));
        }
    }

    private void settleInTransaction(Runnable action) {
        try {
            transactions.executeWithoutResult(status -> action.run());
        } catch (RuntimeException error) {
            log.warn("Derivative settlement deferred: {}", error.toString());
        }
    }

    void expireOption(long positionId) {
        OpenRow candidate = store.findOpenPosition(positionId);
        if (candidate == null || !"OPTION".equals(candidate.kind())) return;
        store.lockUser(candidate.userId());
        OpenRow row = store.lockOpenPosition(positionId);
        if (row == null || !"OPTION".equals(row.kind())) return;
        Instant now = clock.get().instant();
        if (row.expiryAt() != null && row.expiryAt().toInstant().isAfter(now)) return;
        double index = indexHistory.fixing(row.expiryAt().toInstant());
        boolean call = "CALL".equals(row.optionType());
        double points = DerivativePricing.intrinsic(index, row.strike(), call);
        long proceeds = Math.round(points * DerivativePricing.CONTRACT_MULTIPLIER) * row.quantity();
        long realized = proceeds - row.entryNotional();
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", proceeds, row.userId());
        jdbc.update("""
                UPDATE derivative_positions SET status = 'EXPIRED', quantity = 0, mark_price = ?, mark_value = 0,
                  realized_pnl = realized_pnl + ?, closed_at = ?, close_reason = 'EXPIRY' WHERE id = ?
                """, points, realized, Timestamp.from(now), row.id());
        store.recordTrade(row.userId(), row.id(), "EXPIRE", "OPTION", row.symbol(), row.side(), row.quantity(),
                points, proceeds, 0);
    }

    private void liquidate(long userId, long positionId) {
        // The scheduler already knows the owner. Do not start an MVCC snapshot by
        // looking it up before waiting for the account lock. In this short transaction,
        // loadPrices() must be the first consistent read, after both locking reads.
        store.lockUser(userId);
        OpenRow row = store.lockOpenPosition(positionId);
        if (row == null || row.userId() != userId || !"FUTURE".equals(row.kind())) return;
        DerivativeBaskets.Values prices = quotes.loadPrices();
        quotes.requireFresh(prices, "ETFIDX");
        double index = prices.index();
        long pnl = Math.round((index - row.entryPrice()) * DerivativePricing.CONTRACT_MULTIPLIER
                * row.quantity() * ("SHORT".equals(row.side()) ? -1 : 1));
        long equity = row.margin() + pnl;
        if (equity > row.margin() * DerivativePricing.MAINTENANCE_RATE) return;
        long refund = Math.max(0, equity);
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", refund, row.userId());
        jdbc.update("""
                UPDATE derivative_positions SET status = 'LIQUIDATED', quantity = 0, mark_price = ?, mark_value = 0,
                  realized_pnl = realized_pnl + ?, closed_at = ?, close_reason = 'LIQUIDATION' WHERE id = ?
                """, index, refund - row.margin(), Timestamp.from(clock.get().instant()), row.id());
        store.recordTrade(row.userId(), row.id(), "LIQUIDATE", "FUTURE", row.symbol(), row.side(), row.quantity(),
                index, refund, 0);
    }
}
