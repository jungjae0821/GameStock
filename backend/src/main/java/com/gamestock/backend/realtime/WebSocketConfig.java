package com.gamestock.backend.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final MarketSocketHandler handler;
    private final SubscriptionSocketHandler subscriptions;
    public WebSocketConfig(MarketSocketHandler handler,SubscriptionSocketHandler subscriptions) { this.handler = handler;this.subscriptions=subscriptions; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/market").setAllowedOriginPatterns("*");
        registry.addHandler(subscriptions,"/ws/stream").setAllowedOriginPatterns("*");
    }
}
