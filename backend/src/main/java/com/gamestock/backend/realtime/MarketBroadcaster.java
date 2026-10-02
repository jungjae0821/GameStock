package com.gamestock.backend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.market.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One serialized snapshot per subscribed symbol/window, regardless of socket or fill count. */
@Component
public class MarketBroadcaster {
    private final ConnectionManager connections;
    private final EventFanout fanout;
    private final MarketService market;
    private final ObjectMapper json;
    private final AtomicBoolean dirty=new AtomicBoolean(true);
    private final Map<String,Object> pending=new ConcurrentHashMap<>();
    private final Map<String,String> last=new ConcurrentHashMap<>();
    private final Map<String,String> summaries=new ConcurrentHashMap<>();
    private static final class AccountState {
        long sequence;
        Object portfolio,settlements;
        String encoded;
    }
    private final Map<Long,AccountState> portfolios=new ConcurrentHashMap<>();
    public MarketBroadcaster(ConnectionManager connections,EventFanout fanout,MarketService market,ObjectMapper json){this.connections=connections;this.fanout=fanout;this.market=market;this.json=json;}
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
    public void changed(MarketChangedEvent event){
        if(event.symbols().isEmpty())dirty.set(true);else pending.putAll(event.symbols());
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
    public void user(UserMarketEvent event){
        AccountState state=portfolios.computeIfAbsent(event.userId(),k->new AccountState());
        synchronized(state){
            Object payload=event.payload();
            if(payload instanceof Map<?,?> values&&values.containsKey("portfolio")){
                if(event.sequence()>state.sequence){
                    state.sequence=event.sequence();state.portfolio=values.get("portfolio");state.encoded=encode(state.portfolio);
                    if(values.containsKey("settlements"))state.settlements=values.get("settlements");
                }else{
                    // Preserve the individual order event, but never regress a newer account snapshot.
                    Map<Object,Object> withoutStaleAccount=new HashMap<>(values);withoutStaleAccount.remove("portfolio");withoutStaleAccount.remove("settlements");payload=withoutStaleAccount;
                }
            }
            publish("user:"+event.userId(),event.type(),payload);
        }
    }
    @Scheduled(fixedDelayString="${market.websocket.interval-ms:100}")
    public void flush(){
        connections.sweep();Set<String> subscribed=new HashSet<>(connections.subscribedSymbols());
        if(fanout.distributed())subscribed.addAll(pending.keySet());
        last.keySet().retainAll(subscribed);summaries.keySet().retainAll(subscribed);
        boolean refresh=dirty.getAndSet(false);
        for(String symbol:subscribed){
            Object value=pending.remove(symbol);
            if(refresh||!last.containsKey(symbol))value=market.symbolSnapshot(symbol);
            if(value==null)continue;
            String encoded=encode(Map.of("type","MARKET_SYMBOL","payload",value));
            if(!encoded.equals(last.put(symbol,encoded))){
                String summary=summary(value);
                boolean quoteChanged=!summary.equals(summaries.put(symbol,summary));
                fanout.publish(new EventFanout.Envelope("market:"+symbol,UUID.randomUUID().toString(),encoded,quoteChanged?summary:null));
            }
        }
        pending.keySet().retainAll(subscribed);
    }
    /** Valuation can change without a fill. Only connected accounts are checked and only changes sent. */
    @Scheduled(fixedDelay=2000)
    public void valuations(){
        Set<Long> users=connections.connectedUsers();portfolios.keySet().retainAll(users);
        for(long user:users){
            AccountState state=portfolios.computeIfAbsent(user,k->new AccountState());long before;
            synchronized(state){before=state.sequence;}
            var portfolio=market.portfolio(user);String encoded=encode(portfolio);
            synchronized(state){
                if(before!=state.sequence)continue;
                if(!encoded.equals(state.encoded)){
                    state.portfolio=portfolio;state.encoded=encoded;
                    publish("user:"+user,"PORTFOLIO_UPDATED",Map.of("portfolio",portfolio));
                }
            }
        }
    }
    public void accountSnapshot(String socket,long user){
        AccountState state=portfolios.computeIfAbsent(user,k->new AccountState());long before;
        synchronized(state){before=state.sequence;}
        var portfolio=market.portfolio(user);var settlements=market.settlements(user);
        synchronized(state){
            if(before==state.sequence||state.portfolio==null){state.portfolio=portfolio;state.encoded=encode(portfolio);state.settlements=settlements;}
            connections.direct(socket,encode(Map.of("type","AUTHENTICATED","payload",Map.of("portfolio",state.portfolio,"settlements",state.settlements==null?settlements:state.settlements))));
        }
    }
    public void invalidate(){dirty.set(true);}
    /** Initial state belongs to this connection even when the symbol has not changed. */
    public void initial(String id,Set<String> symbols,Set<String> details){
        for(String code:symbols){
            var snapshot=market.symbolSnapshot(code);
            connections.direct(id,details.contains(code)?encode(Map.of("type","MARKET_SYMBOL","payload",snapshot)):summary(snapshot));
        }
    }
    private String summary(Object value){
        if(value instanceof Map<?,?> map&&map.containsKey("stock"))return encode(Map.of("type","MARKET_SYMBOL","payload",Map.of("symbol",map.get("symbol"),"stock",map.get("stock"))));
        return encode(Map.of("type","MARKET_SYMBOL","payload",value));
    }
    private void publish(String channel,String type,Object payload){fanout.publish(new EventFanout.Envelope(channel,UUID.randomUUID().toString(),encode(Map.of("type",type,"payload",payload))));}
    String encode(Object object){try{return json.writeValueAsString(object);}catch(Exception error){throw new IllegalStateException("Cannot serialize market event",error);}}
}
