package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

import static com.gamestock.backend.market.DerivativeModels.*;
import static com.gamestock.backend.market.DerivativePositionStore.PositionRow;
import static com.gamestock.backend.market.DerivativePositionStore.instant;
import static com.gamestock.backend.market.DerivativePositionStore.won;
import static com.gamestock.backend.market.DerivativeQuotes.FUTURE_CODE;
import static com.gamestock.backend.market.DerivativeQuotes.basketCode;
import static com.gamestock.backend.market.DerivativeQuotes.etfDefinition;

/**
 * 모의 ETF·선물·옵션 시장.
 *
 * <p>모든 상품은 기존 주식 계좌의 현금으로 결제한다. ETF는 NAV로 즉시 매매하고,
 * 선물은 레버리지 증거금을 잡은 뒤 평가손익을 반영하며, 옵션은 블랙-숄즈 모의
 * 프리미엄을 지불하고 만기에 내재가치로 현금결제한다. 실제 금융상품이 아니다.
 *
 * <p>이 클래스는 계좌 조회와 주문·청산을 맡고, 나머지는 생성자에서 만드는 package-private
 * 협력 객체에 위임한다: 시세·신선도({@link DerivativeQuotes}), 저장·잠금({@link DerivativePositionStore}),
 * 평가 갱신·만기·강제청산({@link DerivativeSettlement}), 공개 차트 집계({@link DerivativeChartQueries}).
 * 협력 객체는 Spring 빈이 아니므로 순환 의존이 생기지 않고, 같은 JdbcTemplate으로 이 서비스의
 * 트랜잭션에 참여한다. 시계 교체({@link #setClock})는 공급자를 통해 모두에게 즉시 반영된다.
 */
@Service
public class DerivativeService {
    private static final Logger log = LoggerFactory.getLogger(DerivativeService.class);
    private static final int MAX_ORDER_QUANTITY = 100;
    private static final int MAX_OPEN_POSITIONS = 60;
    private static final long MAX_ACCOUNT_EXPOSURE = 10_000_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private Clock clock = Clock.systemUTC();
    private final DerivativeIndexHistory indexHistory;
    private Instant nextHistoryRetention=Instant.EPOCH;
    void setClock(Clock clock) { this.clock = clock; }
    private final DerivativePriceOracle oracle;
    private final DerivativeBaskets baskets;
    private DerivativeMarketImpact marketImpact;
    private final DerivativeQuotes quotes;
    private final DerivativePositionStore store;
    private final DerivativeSettlement settlement;
    private final DerivativeChartQueries charts;

    @org.springframework.beans.factory.annotation.Autowired
    void configureMarketImpact(DerivativeMarketImpact impact) { this.marketImpact = impact; }

    public DerivativeService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.indexHistory = new DerivativeIndexHistory(jdbc);
        this.oracle = new DerivativePriceOracle(jdbc);
        this.baskets = new DerivativeBaskets(jdbc);
        this.transactions = new TransactionTemplate(transactionManager);
        this.quotes = new DerivativeQuotes(baskets, indexHistory, () -> this.clock);
        this.store = new DerivativePositionStore(jdbc, () -> this.clock);
        this.settlement = new DerivativeSettlement(jdbc, transactions, () -> this.clock, indexHistory, oracle, baskets,
                quotes, store, () -> this.marketImpact);
        this.charts = new DerivativeChartQueries(jdbc, () -> this.clock);
    }

    // Production migration is serialized by MarketSchema; tests may initialize explicitly.
    public void ensureTables() {
        ensureTables(jdbc);
    }

    /** 스키마 마이그레이션에서도 호출한다. 여러 번 실행해도 안전하다. */
    static void ensureTables(JdbcTemplate jdbc) {
        DerivativePositionStore.ensureTables(jdbc);
    }

    // ------------------------------------------------------------ 공개 시세

    public DerivativeSnapshot snapshot() {
        return quotes.snapshot();
    }

    private DerivativeBaskets.Values loadPrices() { return quotes.loadPrices(); }

    private void checkExposure(long user, long additional, DerivativeBaskets.Values prices) {
        double index = prices.index();
        Map<String, Double> navs = quotes.etfNavs(prices, index);
        double existing = 0;
        for (PositionRow row : store.positionRows(user)) {
            // Adding risk requires a current valuation of the holdings as well as the new product.
            // Closing positions does not use this check, so stale unrelated assets cannot trap exits.
            quotes.requireFresh(prices, basketCode(row.kind(), row.symbol()));
            existing += switch (row.kind()) {
                case "ETF" -> Math.max(row.entryNotional(), row.quantity() * navs.get(row.symbol()));
                case "FUTURE" -> Math.max(row.entryNotional(), row.quantity() * index * DerivativePricing.CONTRACT_MULTIPLIER);
                case "OPTION" -> row.quantity() * Math.max(row.strike(), index) * DerivativePricing.CONTRACT_MULTIPLIER;
                default -> row.entryNotional();
            };
        }
        if (existing + additional > MAX_ACCOUNT_EXPOSURE)
            throw new IllegalArgumentException("계좌의 파생상품 총 명목금액 한도는 " + won(MAX_ACCOUNT_EXPOSURE) + "입니다.");
    }

    // ------------------------------------------------------------ 계좌

    @Transactional
    public Account account(long userId) {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        List<PositionRow> rows = store.positionRows(userId);
        Instant now = clock.instant();
        DerivativeBaskets.Values prices = loadPrices();
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

    // ------------------------------------------------------------ 주문

    @Transactional
    public OrderResult open(long userId, OrderRequest request) {
        if (request == null) throw new IllegalArgumentException("주문 내용을 확인해 주세요.");
        int quantity = request.quantity();
        if (quantity < 1 || quantity > MAX_ORDER_QUANTITY)
            throw new IllegalArgumentException("수량은 1~" + MAX_ORDER_QUANTITY + " 사이로 입력해 주세요.");
        String kind = normalized(request.kind(), "상품 종류");
        String side = normalized(request.side(), "방향");
        store.requireTradingEnabled();
        store.lockUser(userId);
        int openCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM derivative_positions WHERE user_id = ? AND status = 'OPEN'", Integer.class, userId);
        if (openCount >= MAX_OPEN_POSITIONS && !("ETF".equals(kind) && "SELL".equals(side)))
            throw new IllegalArgumentException("동시에 보유할 수 있는 파생 포지션은 " + MAX_OPEN_POSITIONS + "개까지입니다.");

        DerivativeBaskets.Values prices = loadPrices();
        quotes.requireFresh(prices, basketCode(kind, request.symbol()));
        // Exposure always uses the higher current valuation, regardless of the new order's side.
        DerivativeBaskets.Values exposurePrices = prices.execution(true);
        boolean higher = "BUY".equals(side) || "LONG".equals(side) || "CALL".equals(side);
        prices = prices.execution(higher);
        double index = prices.index();
        Instant now = clock.instant();
        if ("ETF".equals(kind)) {
            if (!"BUY".equals(side) && !"SELL".equals(side))
                throw new IllegalArgumentException("ETF 방향은 BUY 또는 SELL입니다.");
            if ("SELL".equals(side)) return closeEtfBySymbol(userId, request.symbol(), quantity, prices, index);
            return openEtf(userId, request.symbol(), quantity, prices, index, exposurePrices);
        }
        if ("FUTURE".equals(kind)) return openFuture(userId, side, quantity, request.leverage(), index, exposurePrices);
        if ("OPTION".equals(kind)) return openOption(userId, request, quantity, index, now, exposurePrices);
        throw new IllegalArgumentException("상품 종류는 ETF, FUTURE, OPTION 중 하나입니다.");
    }

    private OrderResult openEtf(long userId, String symbol, int quantity, DerivativeBaskets.Values prices, double index,
                               DerivativeBaskets.Values exposurePrices) {
        DerivativeBaskets.Definition definition = etfDefinition(symbol);
        double nav = quotes.etfNavs(prices, index).getOrDefault(definition.code(), 1000.0);
        long amount = Math.round(nav * quantity);
        long fee = DerivativePricing.fee(amount, DerivativePricing.ETF_FEE_RATE);
        checkExposure(userId, amount, exposurePrices);
        store.debit(userId, amount, fee, "ETF 매수 금액");
        long id = store.insertPosition(userId, "ETF", definition.code(), definition.name(), "LONG", quantity,
                nav, amount, 0, 1, null, 0, null, amount, prices.version());
        store.recordTrade(userId, id, "OPEN", "ETF", definition.code(), "LONG", quantity, nav, -(amount + fee), fee);
        return new OrderResult(definition.name() + " " + quantity + "좌 매수 · " + won(amount + fee), account(userId));
    }

    private OrderResult openFuture(long userId, String side, int quantity, Integer requestedLeverage, double index,
                                  DerivativeBaskets.Values exposurePrices) {
        if (!"LONG".equals(side) && !"SHORT".equals(side))
            throw new IllegalArgumentException("선물 방향은 LONG 또는 SHORT입니다.");
        int leverage = requestedLeverage == null ? 5 : requestedLeverage;
        if (!DerivativePricing.LEVERAGES.contains(leverage))
            throw new IllegalArgumentException("레버리지는 " + DerivativePricing.LEVERAGES + " 중에서 선택해 주세요.");
        long notional = DerivativePricing.contractNotional(index, quantity);
        long margin = (long) Math.ceil(notional / (double) leverage);
        long fee = DerivativePricing.fee(notional, DerivativePricing.FUTURE_FEE_RATE);
        checkExposure(userId, DerivativePricing.contractNotional(exposurePrices.index(), quantity), exposurePrices);
        store.debit(userId, margin, fee, "선물 증거금");
        long id = store.insertPosition(userId, "FUTURE", FUTURE_CODE, "게임주 지수 선물 " + leverage + "x",
                side, quantity, index, notional, margin, leverage, null, 0, null, margin, exposurePrices.version());
        store.recordTrade(userId, id, "OPEN", "FUTURE", FUTURE_CODE, side, quantity, index, -(margin + fee), fee);
        return new OrderResult("지수 선물 " + ("LONG".equals(side) ? "매수" : "매도") + " " + quantity
                + "계약 · 증거금 " + won(margin), account(userId));
    }

    private OrderResult openOption(long userId, OrderRequest request, int quantity, double index, Instant now,
                                  DerivativeBaskets.Values exposurePrices) {
        String cycleId = normalized(request.cycleId(), "만기 회차");
        if (!DerivativePricing.cycleIds().contains(cycleId))
            throw new IllegalArgumentException("지원하지 않는 옵션 만기 회차입니다.");
        String type = normalized(request.side(), "옵션 종류");
        if (!"CALL".equals(type) && !"PUT".equals(type))
            throw new IllegalArgumentException("옵션 종류는 CALL 또는 PUT입니다.");
        if (request.strike() == null) throw new IllegalArgumentException("행사가를 선택해 주세요.");
        double strike = request.strike();
        if (!Double.isFinite(strike) || strike <= 0 || Math.abs(strike / index - 1) > 0.06)
            throw new IllegalArgumentException("현재 지수 ±6% 안의 행사가만 거래할 수 있습니다.");
        Instant expiry = DerivativePricing.expiryFor(cycleId, now);
        if (request.expiryEpoch() != null && request.expiryEpoch() != expiry.getEpochSecond())
            throw new IllegalArgumentException("만기가 지난 옵션입니다. 새 회차를 선택해 주세요.");
        double points = DerivativePricing.optionPrice(index, strike,
                DerivativePricing.yearsLeft(expiry, now), quotes.currentVolatility(), "CALL".equals(type));
        long perContract = DerivativePricing.premiumWon(points);
        long premium = perContract * quantity;
        long fee = DerivativePricing.fee(premium, DerivativePricing.OPTION_FEE_RATE);
        checkExposure(userId, DerivativePricing.contractNotional(Math.max(exposurePrices.index(), strike), quantity), exposurePrices);
        store.debit(userId, premium, fee, "옵션 프리미엄");
        String name = DerivativePricing.cycleLabel(cycleId) + " " + ("CALL".equals(type) ? "콜" : "풋")
                + " " + String.format(Locale.KOREA, "%,.0f", strike);
        String symbol = cycleId + "-" + ("CALL".equals(type) ? "C" : "P") + (long) strike;
        long id = store.insertPosition(userId, "OPTION", symbol, name, type, quantity, points, premium,
                0, 1, type, strike, Timestamp.from(expiry), premium, exposurePrices.version());
        store.recordTrade(userId, id, "OPEN", "OPTION", symbol, type, quantity, points, -(premium + fee), fee);
        return new OrderResult(name + " " + quantity + "계약 매수 · 프리미엄 " + won(premium), account(userId));
    }

    @Transactional
    public OrderResult close(long userId, long positionId, int quantity) {
        if (quantity < 1 || quantity > MAX_ORDER_QUANTITY)
            throw new IllegalArgumentException("수량은 1~" + MAX_ORDER_QUANTITY + " 사이로 입력해 주세요.");
        store.requireTradingEnabled();
        store.lockUser(userId);
        PositionRow row = store.lockPosition(userId, positionId);
        if (row == null) throw new IllegalArgumentException("청산할 포지션을 찾을 수 없습니다.");
        if (!"OPEN".equals(row.status())) throw new IllegalArgumentException("이미 종료된 포지션입니다.");
        if (quantity > row.quantity())
            throw new IllegalArgumentException("보유 수량 " + row.quantity() + "계약을 초과했습니다.");
        if ("OPTION".equals(row.kind()) && row.expiryAt() != null && !row.expiryAt().toInstant().isAfter(clock.instant())) {
            settlement.expireOption(positionId);
            return new OrderResult("만기 옵션을 정산했습니다.", account(userId));
        }
        return closeInternal(row, quantity, "CLOSE");
    }

    /**
     * A collateral sale rechecks its cash shortfall only after obtaining the account and position locks.
     * The loan service supplies the fee/slippage buffer and may accrue interest under the same lock.
     * Returns true only when a successful partial close leaves contracts for another attempt.
     */
    @Transactional
    public boolean closeForCollateral(long userId, long positionId, LongSupplier bufferedCashNeeded) {
        store.requireTradingEnabled();
        store.lockUser(userId);
        PositionRow row = store.lockPosition(userId, positionId);
        if (row == null || !"OPEN".equals(row.status()) || row.quantity() <= 0) return false;
        long needed = bufferedCashNeeded.getAsLong();
        if (needed <= 0) return false;
        if ("OPTION".equals(row.kind()) && row.expiryAt() != null && !row.expiryAt().toInstant().isAfter(clock.instant())) {
            settlement.expireOption(positionId);
            return false;
        }
        // Marks estimate the size; closeInternal still uses the current protected execution price.
        // If proceeds fall short, the caller can retry the remaining contracts with their new mark.
        int quantity = Math.min(row.quantity(), MAX_ORDER_QUANTITY);
        if (row.markValue() > 0)
            quantity = (int) Math.min(quantity, Math.max(1, Math.ceil(needed * (double) row.quantity() / row.markValue())));
        closeInternal(row, quantity, "CLOSE");
        return quantity < row.quantity();
    }

    private OrderResult closeEtfBySymbol(long userId, String symbol, int quantity,
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

    private OrderResult closeInternal(PositionRow row, int quantity, String action) {
        Instant now = clock.instant();
        DerivativeBaskets.Values prices = loadPrices();
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
        return new OrderResult(row.name() + " " + quantity + "계약 청산 · 정산 " + won(cashFlow), account(row.userId()));
    }

    // ------------------------------------------------------------ 만기·청산

    /**
     * 평가 갱신과 만기·강제청산. 여러 인스턴스가 실행해도 각 포지션을
     * 짧은 트랜잭션에서 잠그므로 같은 포지션을 두 번 결제하지 않는다.
     * 같은 인스턴스 안의 갱신은 이 서비스의 모니터({@code synchronized})로 직렬화한다.
     * 실제 갱신은 {@link DerivativeSettlement#refresh()}가 이 모니터 안에서 수행한다.
     */
    @Scheduled(fixedDelayString = "${gamestock.derivatives.refresh-ms:3000}",
            initialDelayString = "${gamestock.derivatives.initial-delay-ms:5000}")
    public synchronized void refreshMarks() {
        try {
            settlement.refresh();
            if(!clock.instant().isBefore(nextHistoryRetention)) {
                indexHistory.retain(clock.instant());
                nextHistoryRetention=clock.instant().plusSeconds(3600);
            }
        } catch (RuntimeException error) {
            log.warn("Derivative mark refresh failed: {}", error.toString());
        }
    }

    // ------------------------------------------------------------ 도우미

    private static long portion(long total, int quantity, int originalQuantity) {
        if (originalQuantity <= 0) return 0;
        if (quantity >= originalQuantity) return total;
        return Math.round(total * (double) quantity / originalQuantity);
    }

    private static String normalized(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "를 확인해 주세요.");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ 공개 차트

    /** 옵션 거래 흐름. 집계와 5초 캐시는 {@link DerivativeChartQueries}가 맡는다. */
    public OptionFlow optionFlow(String range, String interval) {
        return charts.optionFlow(range, interval);
    }

    /** 지수 선물 봉 차트. */
    public List<IndexCandle> futureChart(String range) {
        return charts.futureChart(range);
    }

    /** 한국시간 오늘 0시부터의 게임주 지수 10분 봉. */
    public List<IndexCandle> indexToday() {
        return charts.indexToday();
    }
}
