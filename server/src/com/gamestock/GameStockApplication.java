package com.gamestock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** GameStock 개발용 API 및 정적 파일 서버. */
public final class GameStockApplication {
    private static final int PORT = 8080;
    private final MarketService market = new MarketService();
    private final Path frontend = Path.of("frontend").toAbsolutePath().normalize();

    public static void main(String[] args) throws IOException {
        new GameStockApplication().start();
    }

    private void start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/health", this::health);
        server.createContext("/api/stocks", this::stocks);
        server.createContext("/api/market-events", this::marketEvents);
        server.createContext("/api/portfolio", this::portfolio);
        server.createContext("/api/orders", this::orders);
        server.createContext("/", this::staticFile);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf("GameStock 서버가 실행되었습니다: http://localhost:%d%n", PORT);
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"status\":\"ok\",\"time\":\"" + Instant.now() + "\"}");
    }

    private void stocks(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        json(exchange, 200, market.stocksJson());
    }

    private void marketEvents(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        json(exchange, 200, market.eventsJson());
    }

    private void portfolio(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        json(exchange, 200, market.portfolioJson());
    }

    private void orders(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        try (InputStream input = exchange.getRequestBody()) {
            String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> order = JsonFields.parse(body);
            String result = market.placeOrder(order.get("stockCode"), order.get("side"), order.get("quantity"));
            json(exchange, 201, result);
        } catch (IllegalArgumentException e) {
            json(exchange, 400, "{\"message\":\"" + JsonFields.escape(e.getMessage()) + "\"}");
        }
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        URI uri = exchange.getRequestURI();
        String requested = uri.getPath().equals("/") ? "index.html" : uri.getPath().substring(1);
        Path target = frontend.resolve(requested).normalize();
        if (!target.startsWith(frontend) || !Files.isRegularFile(target)) {
            text(exchange, 404, "찾을 수 없는 페이지입니다.", "text/plain; charset=UTF-8");
            return;
        }
        String type = target.toString().endsWith(".css") ? "text/css; charset=UTF-8"
                : target.toString().endsWith(".js") ? "application/javascript; charset=UTF-8"
                : "text/html; charset=UTF-8";
        byte[] bytes = Files.readAllBytes(target);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private boolean method(HttpExchange exchange, String expected) throws IOException {
        if (exchange.getRequestMethod().equals(expected)) return true;
        exchange.getResponseHeaders().set("Allow", expected);
        json(exchange, 405, "{\"message\":\"허용되지 않은 요청 방식입니다.\"}");
        return false;
    }

    private void json(HttpExchange exchange, int status, String payload) throws IOException {
        text(exchange, status, payload, "application/json; charset=UTF-8");
    }

    private void text(HttpExchange exchange, int status, String payload, String type) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
