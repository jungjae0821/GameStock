package com.gamestock.backend.market;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 운영 봇 한 명이 이번 1분에 낼 주문 하나를 고른다. DB를 보지 않는 순수 계산이라 테스트에서
 * 시세와 계좌를 직접 넣어 볼 수 있다. 매도가 매수보다 먼저이고, {@code buyBlocked}면 매수하지 않는다.
 */
final class PersonaDecisions {
    static final int MAX_ORDER_QUANTITY = 1000;

    /** price는 지금 가격, weekHigh는 최근 7거래일 종가 최고, prevClose는 전날 종가, recent는 약 30분 전 가격. */
    record Quote(String code, long price, long weekHigh, long prevClose, long recent) {
        double dayChange() { return change(price, prevClose); }
        double momentum() { return change(price, recent); }
        double drawdown() { return weekHigh <= 0 ? 0 : (weekHigh - price) / (double) weekHigh; }
    }
    record Holding(String code, long quantity, long averagePrice, long price, long heldMinutes) {
        double returnRate() { return change(price, averagePrice); }
    }
    record Account(long cash, long netAsset, List<Holding> holdings) { }
    record Order(String code, String side, int quantity, String reason) { }

    private PersonaDecisions() { }

    static Optional<Order> decide(Persona persona, Account account, List<Quote> quotes, boolean buyBlocked, Random random) {
        List<Quote> listed = quotes.stream().filter(q -> q.price() > 0).toList();
        Optional<Order> order = switch (persona.style()) {
            case LONG -> longTerm(persona, account, listed, buyBlocked, random);
            case SCALP -> scalper(account, listed, buyBlocked, random);
            case NEWBIE -> newbie(persona, account, listed, buyBlocked, random);
        };
        return order.map(o -> scaled(o, persona.orderScale()));
    }

    /** 매수 수량에만 {@code orderScale}을 곱한다. 매도는 들고 있는 만큼 그대로 판다. */
    private static Order scaled(Order order, double scale) {
        if (!order.side().equals("BUY") || scale >= 1) return order;
        int quantity = (int) Math.max(1, Math.round(order.quantity() * scale));
        return new Order(order.code(), order.side(), quantity, order.reason());
    }

    // 장기투자형: 많이 빠진 종목을 나눠 담고 오래 들고 있다. 25% 넘게 오르면 절반만 판다.
    private static Optional<Order> longTerm(Persona persona, Account account, List<Quote> quotes, boolean buyBlocked, Random random) {
        for (Holding holding : account.holdings())
            if (holding.returnRate() >= 0.25)
                return sell(holding, Math.max(1, holding.quantity() / 2), "TAKE_HALF");
        if (buyBlocked || account.holdings().size() >= 5) return Optional.empty();
        long keep = Math.round(account.netAsset() * 0.20);
        if (account.cash() - keep <= 0 || account.cash() < account.netAsset() * 0.25) return Optional.empty();
        List<Quote> dips = quotes.stream().filter(q -> q.drawdown() >= 0.08)
                .sorted(Comparator.comparingDouble((Quote q) -> -(q.drawdown() + (q.code().equals(persona.fandomCode()) ? 0.05 : 0))))
                .limit(3).toList();
        Quote pick = !dips.isEmpty() ? dips.get(random.nextInt(dips.size()))
                : held(account).contains(persona.fandomCode()) ? null : quote(quotes, persona.fandomCode());
        if (pick == null) return Optional.empty();
        long budget = Math.min(Math.round(account.netAsset() * (0.10 + random.nextDouble() * 0.05)), account.cash() - keep);
        return buy(pick, budget, dips.isEmpty() ? "FANDOM" : "DIP");
    }

    // 단타형: 30분 상승세를 타고 +4% 익절, -3% 손절, 세 시간 넘게 들고 있지 않는다.
    private static Optional<Order> scalper(Account account, List<Quote> quotes, boolean buyBlocked, Random random) {
        for (Holding holding : account.holdings()) {
            if (holding.returnRate() >= 0.04) return sell(holding, holding.quantity(), "TAKE_PROFIT");
            if (holding.returnRate() <= -0.03) return sell(holding, holding.quantity(), "STOP_LOSS");
            if (holding.heldMinutes() >= 180) return sell(holding, holding.quantity(), "TIME_EXIT");
        }
        if (buyBlocked || account.holdings().size() >= 3) return Optional.empty();
        Set<String> held = held(account);
        List<Quote> movers = quotes.stream().filter(q -> q.momentum() >= 0.01 && !held.contains(q.code()))
                .sorted(Comparator.comparingDouble(q -> -q.momentum())).limit(3).toList();
        if (movers.isEmpty()) return Optional.empty();
        long budget = Math.min(Math.round(account.netAsset() * (0.25 + random.nextDouble() * 0.10)), Math.round(account.cash() * 0.97));
        return buy(movers.get(random.nextInt(movers.size())), budget, "MOMENTUM");
    }

    // 초보형: 오른 종목을 쫓아 사고, 조금 오르면 바로 팔고, 손절은 늦고 가끔 겁먹고 던진다.
    private static Optional<Order> newbie(Persona persona, Account account, List<Quote> quotes, boolean buyBlocked, Random random) {
        for (Holding holding : account.holdings()) {
            if (holding.returnRate() >= 0.10) return sell(holding, holding.quantity(), "EARLY_PROFIT");
            if (holding.returnRate() <= -0.15 && random.nextDouble() < 0.5) return sell(holding, holding.quantity(), "LATE_STOP");
            Quote quote = quote(quotes, holding.code());
            if (quote != null && quote.dayChange() <= -0.05 && random.nextDouble() < 0.3)
                return sell(holding, holding.quantity(), "PANIC");
        }
        if (buyBlocked || account.holdings().size() >= 2) return Optional.empty();
        Set<String> held = held(account);
        Quote pick = quotes.stream().filter(q -> q.dayChange() >= 0.05 && !held.contains(q.code()))
                .max(Comparator.comparingDouble(Quote::dayChange)).orElse(null);
        String reason = "CHASE";
        if (pick == null && random.nextDouble() < 0.5 && !held.contains(persona.fandomCode())) {
            pick = quote(quotes, persona.fandomCode());
            reason = "FANDOM";
        }
        if (pick == null) return Optional.empty();
        // 대개 현금의 절반쯤, 가끔은 거의 전부를 한 종목에 넣는다.
        double share = random.nextDouble() < 0.15 ? 0.95 : 0.40 + random.nextDouble() * 0.20;
        return buy(pick, Math.round(account.cash() * share), reason);
    }

    private static Optional<Order> buy(Quote quote, long budget, String reason) {
        long quantity = Math.min(MAX_ORDER_QUANTITY, budget / quote.price());
        return quantity < 1 ? Optional.empty() : Optional.of(new Order(quote.code(), "BUY", (int) quantity, reason));
    }

    private static Optional<Order> sell(Holding holding, long quantity, String reason) {
        int capped = (int) Math.min(MAX_ORDER_QUANTITY, Math.min(quantity, holding.quantity()));
        return capped < 1 ? Optional.empty() : Optional.of(new Order(holding.code(), "SELL", capped, reason));
    }

    private static Set<String> held(Account account) {
        return account.holdings().stream().map(Holding::code).collect(Collectors.toSet());
    }

    private static Quote quote(List<Quote> quotes, String code) {
        return quotes.stream().filter(q -> q.code().equals(code)).findFirst().orElse(null);
    }

    private static double change(long now, long before) {
        return before <= 0 ? 0 : (now - before) / (double) before;
    }
}
