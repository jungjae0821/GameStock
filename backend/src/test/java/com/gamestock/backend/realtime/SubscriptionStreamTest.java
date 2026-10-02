package com.gamestock.backend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.auth.AuthService;
import com.gamestock.backend.market.*;
import org.junit.jupiter.api.*;
import org.springframework.web.socket.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionStreamTest {
    LocalEventFanout bus;
    ConnectionManager connections;
    MarketService market;
    MarketMetrics metrics;
    @BeforeEach void setup(){bus=new LocalEventFanout();market=mock(MarketService.class);metrics=new MarketMetrics();connections=new ConnectionManager(bus,metrics,market);}
    @AfterEach void stop()throws Exception{connections.close();}
    WebSocketSession socket(String id){var socket=mock(WebSocketSession.class);when(socket.getId()).thenReturn(id);when(socket.isOpen()).thenReturn(true);connections.connect(socket);return socket;}
    void publish(String channel,String message){bus.publish(new EventFanout.Envelope(channel,UUID.randomUUID().toString(),message));}
    @Test void personalEventsNeverReachOtherAccountsAndMarketEventsRequireSubscription()throws Exception{
        var a=socket("a");var b=socket("b");var anonymous=socket("anon");
        connections.authenticate("a",1);connections.authenticate("b",2);connections.subscribe("a",Set.of("A"));connections.subscribe("b",Set.of("B"));
        publish("user:1","private");publish("market:A","quote");
        verify(a,timeout(2000)).sendMessage(new TextMessage("private"));verify(a,timeout(2000)).sendMessage(new TextMessage("quote"));
        verify(b,never()).sendMessage(any());verify(anonymous,never()).sendMessage(any());
        assertThrows(IllegalArgumentException.class,()->connections.authenticate("a",2));
        connections.subscribe("a",Set.of());publish("market:A","ignored");verify(a,after(100).never()).sendMessage(new TextMessage("ignored"));
    }
    @Test void duplicateFanoutIsDeliveredOnceAndAllSocketsForOneUserReceiveIt()throws Exception{
        var a=socket("a");var b=socket("b");connections.authenticate("a",4);connections.authenticate("b",4);
        var event=new EventFanout.Envelope("user:4","stable-id","fill");bus.publish(event);bus.publish(event);
        verify(a,timeout(2000).times(1)).sendMessage(new TextMessage("fill"));verify(b,timeout(2000).times(1)).sendMessage(new TextMessage("fill"));
    }
    @Test void slowSocketRetainsLatestPublicStateAndPrivateFifoWithoutBlockingFastSocket()throws Exception{
        var slow=socket("slow");var fast=socket("fast");connections.subscribe("slow",Set.of("A"));connections.subscribe("fast",Set.of("A"));connections.authenticate("slow",1);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);List<String> sent=new CopyOnWriteArrayList<>();
        doAnswer(call->{String value=((TextMessage)call.getArgument(0)).getPayload();if(value.equals("first")){entered.countDown();release.await(3,TimeUnit.SECONDS);}sent.add(value);return null;}).when(slow).sendMessage(any());
        try{
            publish("market:A","first");assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTimeout(Duration.ofMillis(500),()->{for(int i=0;i<100;i++)publish("market:A","tick"+i);publish("user:1","fill1");publish("user:1","fill2");});
            verify(fast,timeout(2000)).sendMessage(new TextMessage("tick99"));release.countDown();
            verify(slow,timeout(2000)).sendMessage(new TextMessage("tick99"));assertEquals(List.of("first","fill1","fill2","tick99"),sent);
        }finally{release.countDown();}
    }
    @Test void broadcasterCoalescesThousandFillsAndDoesNotReadUnwatchedSymbols()throws Exception{
        var socket=socket("a");connections.subscribe("a",Set.of("A"));var broadcaster=new MarketBroadcaster(connections,bus,market,new ObjectMapper());
        when(market.symbolSnapshot("A")).thenReturn(Map.of("symbol","A","price",1));broadcaster.flush();
        verify(socket,timeout(2000)).sendMessage(any());clearInvocations(market,socket);
        for(int i=0;i<1000;i++)broadcaster.changed(new MarketChangedEvent(Map.of("A",Map.of("symbol","A","price",i),"B",Map.of("symbol","B","price",i))));
        broadcaster.flush();
        var capture=org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(socket,timeout(2000)).sendMessage(capture.capture());
        assertEquals(999,new ObjectMapper().readTree(capture.getValue().getPayload()).at("/payload/price").asInt());
        verifyNoInteractions(market);assertFalse(connections.subscribedSymbols().contains("B"));
    }
    @Test void forgedUserIdDoesNotAuthenticateAConnection()throws Exception{
        var auth=mock(AuthService.class);var broadcaster=new MarketBroadcaster(connections,bus,market,new ObjectMapper());
        var handler=new SubscriptionSocketHandler(connections,broadcaster,market,auth,new ObjectMapper());var socket=socket("anon");
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"AUTH\",\"userId\":7}"));
        publish("user:7","secret");verify(socket,after(100).never()).sendMessage(new TextMessage("secret"));assertTrue(connections.connectedUsers().isEmpty());
    }
    @Test void quoteOnlySubscriptionsDoNotReceiveOrderBooksAndInitialStateReachesNewViewer()throws Exception{
        var quotes=socket("quotes");var detail=socket("detail");
        connections.subscribe("quotes",Set.of("A"),Set.of());connections.subscribe("detail",Set.of("A"),Set.of("A"));
        bus.publish(new EventFanout.Envelope("market:A","one","full depth","quote only"));
        verify(quotes,timeout(2000)).sendMessage(new TextMessage("quote only"));verify(quotes,never()).sendMessage(new TextMessage("full depth"));
        verify(detail,timeout(2000)).sendMessage(new TextMessage("full depth"));
        bus.publish(new EventFanout.Envelope("market:A","two","changed depth",null));
        verify(quotes,after(100).never()).sendMessage(new TextMessage("changed depth"));
        verify(detail,timeout(2000)).sendMessage(new TextMessage("changed depth"));
        var newcomer=socket("new");connections.subscribe("new",Set.of("A"),Set.of());
        var broadcaster=new MarketBroadcaster(connections,bus,market,new ObjectMapper());
        when(market.symbolSnapshot("A")).thenReturn(Map.of("symbol","A","stock",Map.of("price",10000),"orderbook","private-to-detail"));
        broadcaster.initial("new",Set.of("A"),Set.of());
        var capture=org.mockito.ArgumentCaptor.forClass(TextMessage.class);verify(newcomer,timeout(2000)).sendMessage(capture.capture());
        assertFalse(capture.getValue().getPayload().contains("orderbook"));
    }
    @Test void reorderedCommitCallbacksPreserveOrderEventsWithoutRegressingBalances()throws Exception{
        var socket=socket("a");connections.authenticate("a",1);var broadcaster=new MarketBroadcaster(connections,bus,market,new ObjectMapper());
        var newer=new com.gamestock.backend.market.MarketModels.Portfolio(200,0,200,List.of());
        var older=new com.gamestock.backend.market.MarketModels.Portfolio(100,0,100,List.of());
        broadcaster.user(new UserMarketEvent(1,"ACCOUNT_UPDATED",Map.of("portfolio",newer),2));
        broadcaster.user(new UserMarketEvent(1,"ORDER_CANCELLED",Map.of("portfolio",older,"orderId",7),1));
        var capture=org.mockito.ArgumentCaptor.forClass(TextMessage.class);verify(socket,timeout(2000).times(2)).sendMessage(capture.capture());
        var second=new ObjectMapper().readTree(capture.getAllValues().get(1).getPayload());
        assertEquals(7,second.at("/payload/orderId").asInt());assertTrue(second.at("/payload/portfolio").isMissingNode());
        when(market.portfolio(1)).thenReturn(newer);broadcaster.valuations();verify(socket,after(100).times(2)).sendMessage(any());
    }
    @Test void delayedValuationReadCannotOverwriteAJustCommittedFill()throws Exception{
        var socket=socket("a");connections.authenticate("a",1);var broadcaster=new MarketBroadcaster(connections,bus,market,new ObjectMapper());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var newer=new com.gamestock.backend.market.MarketModels.Portfolio(200,0,200,List.of());
        when(market.portfolio(1)).thenAnswer(call->{entered.countDown();release.await(3,TimeUnit.SECONDS);return new com.gamestock.backend.market.MarketModels.Portfolio(100,0,100,List.of());});
        var executor=Executors.newSingleThreadExecutor();
        try{
            var read=executor.submit(broadcaster::valuations);assertTrue(entered.await(2,TimeUnit.SECONDS));
            broadcaster.user(new UserMarketEvent(1,"ACCOUNT_UPDATED",Map.of("portfolio",newer),2));release.countDown();read.get(3,TimeUnit.SECONDS);
            var capture=org.mockito.ArgumentCaptor.forClass(TextMessage.class);verify(socket,timeout(2000).times(1)).sendMessage(capture.capture());
            assertEquals(200,new ObjectMapper().readTree(capture.getValue().getPayload()).at("/payload/portfolio/cash").asInt());
        }finally{release.countDown();executor.shutdownNow();}
    }
}
