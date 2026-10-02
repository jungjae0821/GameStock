package com.gamestock.backend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.market.MarketChangedEvent;
import com.gamestock.backend.market.MarketService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class MarketSocketHandler extends TextWebSocketHandler {
    private static final Logger log=LoggerFactory.getLogger(MarketSocketHandler.class);
    private static final long SEND_TIMEOUT_NANOS=TimeUnit.SECONDS.toNanos(2);
    private final Map<String,Client> sessions=new ConcurrentHashMap<>();
    private final AtomicBoolean dirty=new AtomicBoolean();
    private final ObjectMapper json;
    private final MarketService market;
    private final ScheduledExecutorService updates=Executors.newSingleThreadScheduledExecutor(factory("market-snapshot"));
    private final ThreadPoolExecutor senders=pool(4,128,"market-socket");
    private final ThreadPoolExecutor closers=pool(2,128,"market-socket-close");
    private volatile boolean stopped;

    private static final class Client {
        final WebSocketSession session;
        final AtomicReference<TextMessage> pending=new AtomicReference<>();
        final AtomicBoolean sending=new AtomicBoolean();
        volatile long started;
        Client(WebSocketSession session){this.session=session;}
    }

    public MarketSocketHandler(ObjectMapper json, MarketService market) {
        this.json = json;
        this.market = market;
    }

    private static ThreadFactory factory(String name) {
        return task->{Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;};
    }
    private static ThreadPoolExecutor pool(int threads,int queue,String name) {
        return new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queue),factory(name));
    }
    @PostConstruct public void start() {
        updates.scheduleWithFixedDelay(()->{
            try {flush();} catch(RuntimeException error){dirty.set(true);log.warn("Market broadcast refresh failed",error);}
        },0,250,TimeUnit.MILLISECONDS);
    }
    @Override public void afterConnectionEstablished(WebSocketSession session) {
        Client client=new Client(session);
        sessions.put(session.getId(),client);
        try {
            market.marketViewerConnected(session.getId());
            dirty.set(true);
        } catch(RuntimeException error) {
            disconnect(client);throw error;
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status) {
        sessions.remove(session.getId());
        market.marketViewerDisconnected(session.getId());
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
    public void broadcast(MarketChangedEvent ignored) {dirty.set(true);}

    // Snapshot work and all network I/O run outside the market's transaction/monitor.
    // Package visibility allows deterministic tests without a real network server.
    void flush() {
        if(stopped || sessions.isEmpty()) return;
        long now=System.nanoTime();
        for(Client client:sessions.values()) {
            if(!client.session.isOpen() || client.started!=0 && now-client.started>SEND_TIMEOUT_NANOS) disconnect(client);
        }
        if(!sessions.isEmpty() && dirty.getAndSet(false)) {
            try {
                var snapshot=market.snapshot();
                TextMessage message=new TextMessage(json.writeValueAsString(Map.of("type","MARKET_UPDATED",
                        "payload",Map.of("stocks",snapshot.stocks(),"events",snapshot.events()))));
                for(Client client:sessions.values()) client.pending.set(message);
            } catch(IOException error) {dirty.set(true);log.warn("Market snapshot serialization failed",error);}
        }
        for(Client client:sessions.values()) submit(client);
    }
    private void submit(Client client) {
        if(stopped || client.pending.get()==null || !client.sending.compareAndSet(false,true)) return;
        try {
            senders.execute(()->{
                try {
                    TextMessage message=client.pending.getAndSet(null);
                    if(message!=null && sessions.get(client.session.getId())==client && client.session.isOpen()) {
                        client.started=System.nanoTime();
                        client.session.sendMessage(message);
                    }
                } catch(IOException | RuntimeException error) {disconnect(client);}
                finally {client.started=0;client.sending.set(false);}
                // One latest message per connection; never build an event backlog.
                if(sessions.get(client.session.getId())==client) submit(client);
            });
        } catch(RejectedExecutionException busy) {client.sending.set(false); /* retry latest state next flush */}
    }
    private void disconnect(Client client) {
        if(!sessions.remove(client.session.getId(),client)) return;
        market.marketViewerDisconnected(client.session.getId());
        client.pending.set(null);
        try {closers.execute(()->{try {client.session.close(CloseStatus.SESSION_NOT_RELIABLE);} catch(IOException ignored) {}});}
        catch(RejectedExecutionException busy) {log.debug("Socket close queue full for {}",client.session.getId());}
    }
    @PreDestroy public void stop() {
        stopped=true;
        updates.shutdownNow();
        for(Client client:sessions.values()) disconnect(client);
        senders.shutdownNow();
        closers.shutdown();
    }
}
