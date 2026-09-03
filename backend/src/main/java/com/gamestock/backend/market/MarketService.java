package com.gamestock.backend.market;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.gamestock.backend.market.MarketModels.*;

/** 현재는 메모리 저장소이며, 다음 단계에서 JPA/MySQL Repository로 바꾼다. */
@Service
public class MarketService {
    private final ApplicationEventPublisher events;
    private final Map<String, Stock> stocks = new LinkedHashMap<>();
    private final Map<String, Integer> holdings = new LinkedHashMap<>();
    private final List<MarketEvent> marketEvents = List.of(
            new MarketEvent("NEXA", "대규모 시즌 업데이트 적용", 8, "positive"),
            new MarketEvent("STAR", "경쟁작 출시 예고", -4, "negative"),
            new MarketEvent("MOMO", "글로벌 누적 이용자 1,000만 달성", 6, "positive")
    );
    private long cash = 1_000_000;

    public MarketService(ApplicationEventPublisher events) {
        this.events = events;
        add("NEXA", "네사: 크로니클", "RPG", 12_450, 4.32);
        add("STAR", "스타라이트 아레나", "액션", 8_230, -2.15);
        add("MOMO", "모모 팜", "캐주얼", 21_430, 7.82);
        add("VOID", "보이드 러너", "슈팅", 5_920, -1.04);
    }

    private void add(String code, String name, String genre, long price, double change) {
        stocks.put(code, new Stock(code, name, genre, price, change, 120_000));
        holdings.put(code, 0);
    }

    public synchronized List<Stock> stocks() { return List.copyOf(stocks.values()); }
    public synchronized List<MarketEvent> marketEvents() { return marketEvents; }
    public synchronized Portfolio portfolio() { return portfolioUnsafe(); }
    public synchronized MarketSnapshot snapshot() { return new MarketSnapshot(stocks(), portfolioUnsafe(), marketEvents); }

    public synchronized OrderResult order(OrderRequest request) {
        String code = request.stockCode().toUpperCase(Locale.ROOT);
        String side = request.side().toUpperCase(Locale.ROOT);
        Stock stock = stocks.get(code);
        if (stock == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        long amount = stock.price() * request.quantity();
        if ("BUY".equals(side)) {
            if (cash < amount) throw new IllegalArgumentException("보유 현금이 부족합니다.");
            cash -= amount;
            holdings.merge(code, request.quantity(), Integer::sum);
        } else if ("SELL".equals(side)) {
            if (holdings.get(code) < request.quantity()) throw new IllegalArgumentException("보유 수량이 부족합니다.");
            cash += amount;
            holdings.merge(code, -request.quantity(), Integer::sum);
        } else {
            throw new IllegalArgumentException("주문 구분은 BUY 또는 SELL이어야 합니다.");
        }
        move(code, "BUY".equals(side) ? 0.12 : -0.12, request.quantity());
        MarketSnapshot snapshot = snapshot();
        events.publishEvent(new MarketChangedEvent(snapshot));
        return new OrderResult("주문이 체결되었습니다.", code, side, request.quantity(), stock.price(), snapshot.portfolio());
    }

    /** 봇의 시장 참여를 단순 시뮬레이션한다. 웹과 앱에는 WebSocket으로 동시에 전달된다. */
    @Scheduled(fixedRate = 5_000)
    public synchronized void simulateBots() {
        List<String> codes = new ArrayList<>(stocks.keySet());
        String code = codes.get(ThreadLocalRandom.current().nextInt(codes.size()));
        double movement = ThreadLocalRandom.current().nextDouble(-0.18, 0.19);
        move(code, movement, ThreadLocalRandom.current().nextInt(20, 121));
        events.publishEvent(new MarketChangedEvent(snapshot()));
    }

    private void move(String code, double movement, int volume) {
        Stock old = stocks.get(code);
        long next = Math.max(100, Math.round(old.price() * (1 + movement / 100)));
        stocks.put(code, new Stock(old.code(), old.name(), old.genre(), next,
                Math.round((old.changePercent() + movement) * 100.0) / 100.0, old.volume() + volume));
    }

    private Portfolio portfolioUnsafe() {
        List<Position> positions = holdings.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(entry -> new Position(entry.getKey(), entry.getValue(), entry.getValue() * stocks.get(entry.getKey()).price())).toList();
        long assetValue = positions.stream().mapToLong(Position::marketValue).sum();
        return new Portfolio(cash, assetValue, cash + assetValue, positions);
    }
}
