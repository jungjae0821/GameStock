package com.gamestock.backend.market;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import static com.gamestock.backend.market.DerivativeModels.*;
import static com.gamestock.backend.market.DerivativePositionStore.PositionRow;
import static com.gamestock.backend.market.DerivativePositionStore.won;
import static com.gamestock.backend.market.DerivativeQuotes.FUTURE_CODE;
import static com.gamestock.backend.market.DerivativeQuotes.basketCode;
import static com.gamestock.backend.market.DerivativeQuotes.etfDefinition;

/**
 * ETF 매수·선물·옵션 신규 진입과 계좌 명목금액 한도 검사. {@link DerivativeService}에서 분리했다.
 *
 * <p>Spring 빈이 아니며 상태를 갖지 않는다. 입력 검증, 거래 가능 여부, 사용자 잠금, 보유 개수 한도는
 * 서비스의 {@code @Transactional} {@code open()}이 먼저 처리하고, 이 객체는 같은 트랜잭션·잠금 안에서
 * 상품별 진입만 수행한다.
 */
final class DerivativeOrderOpener {
    private static final long MAX_ACCOUNT_EXPOSURE = 10_000_000;

    private final DerivativeQuotes quotes;
    private final DerivativePositionStore store;
    private final DerivativeAccountView accounts;

    DerivativeOrderOpener(DerivativeQuotes quotes, DerivativePositionStore store, DerivativeAccountView accounts) {
        this.quotes = quotes;
        this.store = store;
        this.accounts = accounts;
    }

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

    OrderResult openEtf(long userId, String symbol, int quantity, DerivativeBaskets.Values prices, double index,
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
        return new OrderResult(definition.name() + " " + quantity + "좌 매수 · " + won(amount + fee), accounts.account(userId));
    }

    OrderResult openFuture(long userId, String side, int quantity, Integer requestedLeverage, double index,
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
                + "계약 · 증거금 " + won(margin), accounts.account(userId));
    }

    OrderResult openOption(long userId, OrderRequest request, int quantity, double index, Instant now,
                           DerivativeBaskets.Values exposurePrices) {
        String cycleId = DerivativeService.normalized(request.cycleId(), "만기 회차");
        if (!DerivativePricing.cycleIds().contains(cycleId))
            throw new IllegalArgumentException("지원하지 않는 옵션 만기 회차입니다.");
        String type = DerivativeService.normalized(request.side(), "옵션 종류");
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
        return new OrderResult(name + " " + quantity + "계약 매수 · 프리미엄 " + won(premium), accounts.account(userId));
    }
}
