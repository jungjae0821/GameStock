package com.gamestock.backend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.market.MarketChangedEvent;
import com.gamestock.backend.market.MarketService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.*;
import org.springframework.web.socket.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.gamestock.backend.market.MarketModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketSocketHandlerTest {
    private MarketService market;
    private MarketSocketHandler handler;
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() {
        market=mock(MarketService.class);
        when(market.snapshot()).thenReturn(snapshot(10000));
        handler=new MarketSocketHandler(json,market);
    }
    @AfterEach void close(){handler.stop();}
    private MarketSnapshot snapshot(long price){return new MarketSnapshot(List.of(new Stock("A","A","IT",price,0,0)),new Portfolio(999,0,999,List.of()),List.of());}
    private WebSocketSession session(String id) {
        var session=mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);when(session.isOpen()).thenReturn(true);
        return session;
    }
    @Test void failedWakeDoesNotLeaveAPhantomViewerKeepingTheMarketActive() throws Exception {
        var client=session("failed");var closed=new CountDownLatch(1);
        doThrow(new IllegalStateException("wake failed")).when(market).marketViewerConnected("failed");
        doAnswer(call->{closed.countDown();return null;}).when(client).close(any(CloseStatus.class));
        assertThrows(IllegalStateException.class,()->handler.afterConnectionEstablished(client));
        verify(market).marketViewerDisconnected("failed");
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(handler,"sessions")).isEmpty());
        assertTrue(closed.await(2,TimeUnit.SECONDS));
    }
    @Test void invalidationsCoalesceWithoutSnapshotWorkOnThePublishingThread() throws Exception {
        var client=session("client"); var received=new CountDownLatch(1);
        List<String> payloads=new CopyOnWriteArrayList<>();
        doAnswer(call->{payloads.add(((TextMessage)call.getArgument(0)).getPayload());received.countDown();return null;}).when(client).sendMessage(any());
        handler.afterConnectionEstablished(client);
        for(int i=0;i<1000;i++) handler.broadcast(new MarketChangedEvent());
        verify(market).marketViewerConnected("client");verifyNoMoreInteractions(market);
        handler.flush();
        assertTrue(received.await(2,TimeUnit.SECONDS));
        verify(market,times(1)).snapshot();
        var message=json.readTree(payloads.get(0));
        assertEquals("MARKET_UPDATED",message.get("type").asText());
        assertEquals(Set.of("stocks","events"),json.convertValue(message.get("payload"),Map.class).keySet());
        handler.flush(); verify(market,times(1)).snapshot();
    }
    @Test void slowSocketDoesNotBlockPublisherOrHealthyClientAndKeepsOnlyLatestState() throws Exception {
        var slow=session("slow");var fast=session("fast");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var received=new CountDownLatch(2);var healthy=new CountDownLatch(1);
        var inFlight=new AtomicInteger();var maximum=new AtomicInteger();
        List<String> payloads=new CopyOnWriteArrayList<>();
        doAnswer(call->{
            maximum.accumulateAndGet(inFlight.incrementAndGet(),Math::max);
            try {
                if(payloads.isEmpty()){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}
                payloads.add(((TextMessage)call.getArgument(0)).getPayload());received.countDown();
            } finally {inFlight.decrementAndGet();}
            return null;
        }).when(slow).sendMessage(any());
        doAnswer(call->{healthy.countDown();return null;}).when(fast).sendMessage(any());
        try {
            handler.afterConnectionEstablished(slow);handler.afterConnectionEstablished(fast);handler.flush();
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(healthy.await(2,TimeUnit.SECONDS));
            assertTimeout(Duration.ofMillis(500),()->{for(int i=0;i<1000;i++)handler.broadcast(new MarketChangedEvent());});
            for(int i=1;i<=30;i++) {when(market.snapshot()).thenReturn(snapshot(10000+i));handler.broadcast(new MarketChangedEvent());handler.flush();}
            release.countDown();assertTrue(received.await(2,TimeUnit.SECONDS));
            assertEquals(2,payloads.size());assertEquals(1,maximum.get());
            assertEquals(10030,json.readTree(payloads.get(1)).at("/payload/stocks/0/price").asLong());
        } finally {release.countDown();}
    }
    @Test void expiredSendIsDisconnectedWithoutBlockingTheSnapshotWorker() throws Exception {
        var slow=session("slow");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();release.await(5,TimeUnit.SECONDS);return null;}).when(slow).sendMessage(any());
        doAnswer(call->{closed.countDown();release.countDown();return null;}).when(slow).close(any(CloseStatus.class));
        try {
            handler.afterConnectionEstablished(slow);handler.flush();assertTrue(entered.await(2,TimeUnit.SECONDS));
            var sessions=(Map<?,?>)ReflectionTestUtils.getField(handler,"sessions");
            ReflectionTestUtils.setField(sessions.get("slow"),"started",System.nanoTime()-TimeUnit.SECONDS.toNanos(3));
            handler.flush();assertTrue(closed.await(2,TimeUnit.SECONDS));assertTrue(sessions.isEmpty());
        } finally {release.countDown();}
    }
    @Test void onlyCommittedTransactionsTriggerBroadcasts() throws Exception {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean(MarketSocketHandler.class,()->new MarketSocketHandler(json,market){@Override public void start(){}});
            context.refresh();
            var listener=context.getBean(MarketSocketHandler.class);
            listener.afterConnectionEstablished(session("client"));listener.flush();clearInvocations(market);
            var manager=new AbstractPlatformTransactionManager() {
                protected Object doGetTransaction(){return new Object();}
                protected void doBegin(Object transaction,TransactionDefinition definition){}
                protected void doCommit(DefaultTransactionStatus status){}
                protected void doRollback(DefaultTransactionStatus status){}
            };
            var tx=new TransactionTemplate(manager);
            tx.executeWithoutResult(status->{context.publishEvent(new MarketChangedEvent());listener.flush();verifyNoInteractions(market);status.setRollbackOnly();});
            listener.flush();verifyNoInteractions(market);
            tx.executeWithoutResult(status->{context.publishEvent(new MarketChangedEvent());listener.flush();verifyNoInteractions(market);});
            listener.flush();verify(market,times(1)).snapshot();
        }
    }
}
