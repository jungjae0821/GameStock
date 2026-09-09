package com.gamestock.backend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.market.MarketChangedEvent;
import com.gamestock.backend.market.MarketService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MarketSocketHandler extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper json;
    private final MarketService market;

    public MarketSocketHandler(ObjectMapper json, MarketService market) {
        this.json = json;
        this.market = market;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        send(session, new MarketChangedEvent(market.snapshot()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session); }

    @EventListener
    public void broadcast(MarketChangedEvent event) {
        sessions.removeIf(session -> !session.isOpen());
        sessions.forEach(session -> {
            try { send(session, event); } catch (IOException ignored) { sessions.remove(session); }
        });
    }

    private void send(WebSocketSession session, MarketChangedEvent event) throws IOException {
        // 웹소켓은 공개 시장 정보만 전송한다. 사용자별 자산은 인증된 REST API로만 조회한다.
        var snapshot = event.snapshot();
        String payload = json.writeValueAsString(java.util.Map.of("type", "MARKET_UPDATED",
                "payload", java.util.Map.of("stocks", snapshot.stocks(), "events", snapshot.events())));
        session.sendMessage(new TextMessage(payload));
    }
}
