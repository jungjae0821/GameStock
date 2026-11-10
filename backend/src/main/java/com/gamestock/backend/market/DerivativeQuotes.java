package com.gamestock.backend.market;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.gamestock.backend.market.DerivativeModels.*;

/**
 * 공개 시세와 가격 신선도 판정: ETF NAV, 지수 선물, 옵션 체인, 최근 시계열.
 *
 * <p>DerivativeService가 생성하는 package-private 협력 객체다. 시계열 버퍼는 키마다
 * 해당 Deque 객체를 모니터로 잠그며(분리 전과 동일), 서비스 인스턴스당 하나만 존재한다.
 */
final class DerivativeQuotes {
    static final String FUTURE_CODE = "FUTIDX";
    static final List<DerivativeBaskets.Definition> ETFS = DerivativeBaskets.DEFINITIONS;
    private static final int SERIES_LIMIT = 120;

    private final DerivativeBaskets baskets;
    private final DerivativeIndexHistory indexHistory;
    private final Supplier<Clock> clock;
    private final Map<String, Deque<Double>> series = new ConcurrentHashMap<>();

    DerivativeQuotes(DerivativeBaskets baskets, DerivativeIndexHistory indexHistory, Supplier<Clock> clock) {
        this.baskets = baskets;
        this.indexHistory = indexHistory;
        this.clock = clock;
    }

    DerivativeSnapshot snapshot() {
        Instant now = clock.get().instant();
        DerivativeBaskets.Values prices = loadPrices();
        double index = prices.index();
        double previous = prices.basket("ETFIDX").previous();
        List<EtfQuote> etfs = new ArrayList<>(ETFS.size());
        for (DerivativeBaskets.Definition definition : ETFS) etfs.add(etfQuote(definition, prices, index));
        FutureQuote future = new FutureQuote(FUTURE_CODE, "게임주 지수 선물", index, previous,
                percent(index, previous), DerivativePricing.CONTRACT_MULTIPLIER, DerivativePricing.LEVERAGES,
                DerivativePricing.MAINTENANCE_RATE, series("FUTIDX", index));
        double volatility = currentVolatility();
        List<OptionCycle> cycles = new ArrayList<>(3);
        List<OptionContract> contracts = new ArrayList<>();
        for (String cycleId : DerivativePricing.cycleIds()) {
            Instant expiry = DerivativePricing.expiryFor(cycleId, now);
            long secondsLeft = Math.max(0, expiry.getEpochSecond() - now.getEpochSecond());
            cycles.add(new OptionCycle(cycleId, DerivativePricing.cycleLabel(cycleId),
                    DerivativePricing.cycleTag(cycleId), expiry.toString(), expiry.getEpochSecond(), secondsLeft));
            double years = DerivativePricing.yearsLeft(expiry, now);
            for (double strike : DerivativePricing.strikes(index)) {
                for (boolean call : new boolean[]{true, false}) {
                    double points = DerivativePricing.optionPrice(index, strike, years, volatility, call);
                    long premium = DerivativePricing.premiumWon(points);
                    double breakeven = call ? strike + points : strike - points;
                    String type = call ? "CALL" : "PUT";
                    String id = cycleId + "-" + (call ? "C" : "P") + (long) strike + "-" + expiry.getEpochSecond();
                    contracts.add(new OptionContract(id, cycleId, type, strike, points, premium,
                            breakeven, expiry.toString(), expiry.getEpochSecond(), secondsLeft));
                }
            }
        }
        return new DerivativeSnapshot(index, previous, percent(index, previous), etfs, future, cycles,
                contracts, now.toString());
    }

    private EtfQuote etfQuote(DerivativeBaskets.Definition definition, DerivativeBaskets.Values prices, double index) {
        var basket = prices.basket(definition.code());
        List<Constituent> constituents = basket.members().stream()
                .map(stock -> new Constituent(stock.code(), stock.name(), stock.weight(), stock.changePercent()))
                .sorted(Comparator.comparingDouble(Constituent::weight).reversed()).toList();
        double nav = basket.price(), previous = basket.previous();
        return new EtfQuote(definition.code(), definition.name(), definition.theme(), definition.description(),
                nav, previous, percent(nav, previous), constituents, series("ETF:" + definition.code(), nav));
    }

    DerivativeBaskets.Values loadPrices() { return baskets.load(); }

    boolean fresh(DerivativeBaskets.Values prices, String symbol, Instant now) {
        var basket = prices.basket(symbol);
        // An empty sector ETF can hold cash; a tradable index needs an underlying market.
        return basket.fresh(now) && (!"ETFIDX".equals(symbol) || !basket.members().isEmpty());
    }

    void requireFresh(DerivativeBaskets.Values prices, String symbol) {
        if (!fresh(prices, symbol, clock.get().instant()))
            throw new IllegalArgumentException("기준가격을 확인 중입니다. 충분한 체결이 쌓인 뒤 다시 거래해 주세요.");
    }

    static String basketCode(String kind, String symbol) {
        return "ETF".equals(kind) ? etfDefinition(symbol).code() : "ETFIDX";
    }

    Map<String, Double> etfNavs(DerivativeBaskets.Values prices, double index) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (DerivativeBaskets.Definition definition : ETFS) result.put(definition.code(), prices.basket(definition.code()).price());
        return result;
    }

    static DerivativeBaskets.Definition etfDefinition(String symbol) {
        String code = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        return ETFS.stream().filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("상장되지 않은 ETF입니다."));
    }

    private static double percent(double current, double reference) {
        return reference <= 0 ? 0 : Math.round((current - reference) * 10_000.0 / reference) / 100.0;
    }

    // ------------------------------------------------------------ 변동성·시계열

    double currentVolatility() {
        return indexHistory.volatility(clock.get().instant());
    }

    List<Double> series(String key, double value) {
        Deque<Double> values = series.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (values) {
            if (values.isEmpty() || Double.compare(values.peekLast(), value) != 0) values.addLast(value);
            while (values.size() > SERIES_LIMIT) values.removeFirst();
            return new ArrayList<>(values);
        }
    }
}
