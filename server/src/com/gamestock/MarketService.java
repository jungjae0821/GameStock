package com.gamestock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 이후 MySQL Repository로 교체할 수 있는 개발용 메모리 시장 서비스. */
final class MarketService {
    private final Map<String, Stock> stocks = new LinkedHashMap<>();
    private final Map<String, Integer> holdings = new LinkedHashMap<>();
    private long cash = 1_000_000;

    MarketService() {
        add("NEXA", "네사: 크로니클", "RPG", 12_450, 4.32);
        add("STAR", "스타라이트 아레나", "액션", 8_230, -2.15);
        add("MOMO", "모모 팜", "캐주얼", 21_430, 7.82);
        add("VOID", "보이드 러너", "슈팅", 5_920, -1.04);
    }

    private void add(String code, String name, String genre, long price, double change) {
        stocks.put(code, new Stock(code, name, genre, price, change, 120_000));
        holdings.put(code, 0);
    }

    synchronized String stocksJson() {
        return "[" + String.join(",", stocks.values().stream().map(Stock::json).toList()) + "]";
    }

    synchronized String eventsJson() {
        return "["
                + "{\"stockCode\":\"NEXA\",\"title\":\"대규모 시즌 업데이트 적용\",\"impact\":8,\"sentiment\":\"positive\"},"
                + "{\"stockCode\":\"STAR\",\"title\":\"경쟁작 출시 예고\",\"impact\":-4,\"sentiment\":\"negative\"},"
                + "{\"stockCode\":\"MOMO\",\"title\":\"글로벌 누적 이용자 1,000만 달성\",\"impact\":6,\"sentiment\":\"positive\"}"
                + "]";
    }

    synchronized String portfolioJson() {
        long assetValue = holdings.entrySet().stream()
                .mapToLong(entry -> entry.getValue() * stocks.get(entry.getKey()).price()).sum();
        String positions = String.join(",", holdings.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> "{\"stockCode\":\"" + entry.getKey() + "\",\"quantity\":" + entry.getValue()
                        + ",\"marketValue\":" + (entry.getValue() * stocks.get(entry.getKey()).price()) + "}")
                .toList());
        return "{\"cash\":" + cash + ",\"assetValue\":" + assetValue
                + ",\"totalAsset\":" + (cash + assetValue) + ",\"positions\":[" + positions + "]}";
    }

    synchronized String placeOrder(String stockCode, String side, String quantityText) {
        if (stockCode == null || side == null || quantityText == null) throw new IllegalArgumentException("종목, 주문 구분, 수량은 필수입니다.");
        Stock stock = stocks.get(stockCode.toUpperCase(Locale.ROOT));
        if (stock == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        int quantity;
        try { quantity = Integer.parseInt(quantityText); } catch (NumberFormatException e) { throw new IllegalArgumentException("수량은 정수여야 합니다."); }
        if (quantity < 1 || quantity > 1_000) throw new IllegalArgumentException("수량은 1~1,000주만 주문할 수 있습니다.");
        String normalizedSide = side.toUpperCase(Locale.ROOT);
        long amount = stock.price() * quantity;
        if (normalizedSide.equals("BUY")) {
            if (cash < amount) throw new IllegalArgumentException("보유 현금이 부족합니다.");
            cash -= amount;
            holdings.compute(stock.code(), (key, held) -> held + quantity);
        } else if (normalizedSide.equals("SELL")) {
            if (holdings.get(stock.code()) < quantity) throw new IllegalArgumentException("보유 수량이 부족합니다.");
            cash += amount;
            holdings.compute(stock.code(), (key, held) -> held - quantity);
        } else {
            throw new IllegalArgumentException("주문 구분은 BUY 또는 SELL이어야 합니다.");
        }
        double movement = (normalizedSide.equals("BUY") ? 1 : -1) * Math.min(0.35, quantity / 1000.0);
        stocks.put(stock.code(), stock.move(movement, quantity));
        return "{\"message\":\"주문이 체결되었습니다.\",\"stockCode\":\"" + stock.code() + "\",\"side\":\"" + normalizedSide
                + "\",\"quantity\":" + quantity + ",\"price\":" + stock.price() + "}";
    }

    private record Stock(String code, String name, String genre, long price, double changePercent, long volume) {
        Stock move(double movement, int quantity) {
            long nextPrice = Math.max(100, Math.round(price * (1 + movement / 100)));
            return new Stock(code, name, genre, nextPrice, changePercent + movement, volume + quantity);
        }
        String json() {
            return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"genre\":\"" + genre
                    + "\",\"price\":" + price + ",\"changePercent\":" + String.format(Locale.US, "%.2f", changePercent)
                    + ",\"volume\":" + volume + "}";
        }
    }
}
