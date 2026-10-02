package com.gamestock.backend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.auth.AuthService;
import com.gamestock.backend.market.MarketService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.util.*;

/** /ws/stream: AUTH {token}, SUBSCRIBE {symbols:[]} (replaces subscription set), PING.
 * Public subscriptions require no login. Private identity is never taken from a userId message.
 */
@Component
public class SubscriptionSocketHandler extends TextWebSocketHandler {
    private final ConnectionManager connections;
    private final MarketBroadcaster broadcaster;
    private final MarketService market;
    private final AuthService auth;
    private final ObjectMapper json;
    public SubscriptionSocketHandler(ConnectionManager connections,MarketBroadcaster broadcaster,MarketService market,AuthService auth,ObjectMapper json){this.connections=connections;this.broadcaster=broadcaster;this.market=market;this.auth=auth;this.json=json;}
    @Override public void afterConnectionEstablished(WebSocketSession socket){
        socket.setTextMessageSizeLimit(8192);connections.connect(socket);
        try{market.marketViewerConnected(socket.getId());}catch(RuntimeException error){connections.disconnect(socket.getId());market.marketViewerDisconnected(socket.getId());throw error;}
    }
    @Override protected void handleTextMessage(WebSocketSession socket,TextMessage message)throws Exception{
        try{
            var request=json.readTree(message.getPayload());
            switch(request.path("type").asText()){
                case "AUTH"->{
                    var user=auth.requireUser("Bearer "+request.path("token").asText());connections.authenticate(socket.getId(),user.id());
                    broadcaster.accountSnapshot(socket.getId(),user.id());
                }
                case "SUBSCRIBE"->{
                    Set<String> available=new HashSet<>(market.botStockCodes()),requested=new LinkedHashSet<>();
                    if(!request.path("symbols").isArray()||request.path("symbols").size()>32)throw new IllegalArgumentException("Invalid subscription");
                    request.path("symbols").forEach(item->{String code=item.asText().toUpperCase(Locale.ROOT);if(!available.contains(code))throw new IllegalArgumentException("Unknown symbol");requested.add(code);});
                    Set<String> details=new HashSet<>();
                    if(request.path("detailSymbols").isArray())request.path("detailSymbols").forEach(item->details.add(item.asText().toUpperCase(Locale.ROOT)));
                    connections.subscribe(socket.getId(),requested,details);broadcaster.initial(socket.getId(),requested,details);
                }
                case "PING"->connections.direct(socket.getId(),"{\"type\":\"PONG\"}");
                default->throw new IllegalArgumentException("Unknown message type");
            }
        }catch(RuntimeException error){connections.direct(socket.getId(),"{\"type\":\"ERROR\",\"message\":\"Invalid or unauthorized message\"}");}
    }
    @Override public void afterConnectionClosed(WebSocketSession socket,CloseStatus status){connections.disconnect(socket.getId());market.marketViewerDisconnected(socket.getId());}
    @Override public void handleTransportError(WebSocketSession socket,Throwable error){connections.disconnect(socket.getId());market.marketViewerDisconnected(socket.getId());}
}
