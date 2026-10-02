package com.gamestock.backend.realtime;

import com.gamestock.backend.market.MarketMetrics;
import com.gamestock.backend.market.MarketService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Private messages use a bounded FIFO; replaceable public state uses one slot per symbol.
 * A socket has exactly one writer. A slow connection cannot block matching or another socket.
 */
@Component
public class ConnectionManager {
    static final class Client {
        final WebSocketSession socket;
        final Deque<String> privateMessages=new ArrayDeque<>();
        final Map<String,String> latest=new LinkedHashMap<>();
        Set<String> symbols=Set.of();
        Set<String> details=Set.of();
        Long user,identity;
        long authUntil,sendStarted;
        boolean sending;
        Client(WebSocketSession socket){this.socket=socket;}
    }
    private final Map<String,Client> clients=new ConcurrentHashMap<>();
    private final Map<Long,Set<String>> users=new ConcurrentHashMap<>();
    private final Map<String,Set<String>> symbols=new ConcurrentHashMap<>();
    private final MarketMetrics metrics;
    private final MarketService market;
    private final ExecutorService senders=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),r->{Thread t=new Thread(r,"symbol-websocket");t.setDaemon(true);return t;});
    private final ExecutorService closers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),r->{Thread t=new Thread(r,"symbol-websocket-close");t.setDaemon(true);return t;});
    private final AutoCloseable listener;
    private final Map<String,Boolean> seen=Collections.synchronizedMap(new LinkedHashMap<>(){
        protected boolean removeEldestEntry(Map.Entry<String,Boolean> entry){return size()>8192;}
    });
    public ConnectionManager(EventFanout fanout,MarketMetrics metrics,MarketService market){this.metrics=metrics;this.market=market;listener=fanout.listen(this::receive);}
    public void connect(WebSocketSession socket){clients.put(socket.getId(),new Client(socket));metrics.gauge("ws.active",clients.size());}
    public void authenticate(String id,long user){
        Client client=required(id);synchronized(client){
            if(client.identity!=null&&client.identity!=user)throw new IllegalArgumentException("Reconnect to switch identity");
            if(client.user!=null)remove(users,client.user,id);
            client.identity=user;client.user=user;client.authUntil=System.currentTimeMillis()+300000;
            users.computeIfAbsent(user,k->ConcurrentHashMap.newKeySet()).add(id);
        }
    }
    public void subscribe(String id,Set<String> requested){
        subscribe(id,requested,requested);
    }
    public void subscribe(String id,Set<String> requested,Set<String> details){
        if(requested.size()>32)throw new IllegalArgumentException("At most 32 symbols per socket");
        if(!requested.containsAll(details))throw new IllegalArgumentException("Detail symbols must be subscribed");
        Client client=required(id);synchronized(client){
            for(String code:client.symbols)remove(symbols,code,id);
            client.symbols=Set.copyOf(requested);client.details=Set.copyOf(details);client.latest.clear();
            for(String code:requested)symbols.computeIfAbsent(code,k->ConcurrentHashMap.newKeySet()).add(id);
        }
    }
    private Client required(String id){Client client=clients.get(id);if(client==null)throw new IllegalArgumentException("Socket closed");return client;}
    public Set<String> subscribedSymbols(){return Set.copyOf(symbols.keySet());}
    public Set<Long> connectedUsers(){return Set.copyOf(users.keySet());}
    public int size(){return clients.size();}
    public void direct(String id,String json){Client client=clients.get(id);if(client!=null)enqueue(client,null,json);}
    private void receive(EventFanout.Envelope event){
        synchronized(seen){if(seen.put(event.eventId(),true)!=null)return;}
        if(event.channel().startsWith("market:")){
            String code=event.channel().substring(7);for(String id:symbols.getOrDefault(code,Set.of())){Client client=clients.get(id);if(client!=null)synchronized(client){
                String payload=client.details.contains(code)?event.json():event.summaryJson();if(payload!=null)enqueue(client,code,payload);
            }}
        }else if(event.channel().startsWith("user:")){
            long user=Long.parseLong(event.channel().substring(5));
            for(String id:users.getOrDefault(user,Set.of())){Client client=clients.get(id);if(client!=null)synchronized(client){if(client.authUntil>System.currentTimeMillis())enqueue(client,null,event.json());}}
        }
    }
    private void enqueue(Client client,String symbol,String json){
        synchronized(client){
            if(symbol==null){if(client.privateMessages.size()>=256){disconnect(client.socket.getId());return;}client.privateMessages.addLast(json);}
            else client.latest.put(symbol,json);
            schedule(client);
        }
    }
    private void schedule(Client client){
        if(client.sending)return;client.sending=true;
        try{senders.execute(()->send(client));}
        catch(RejectedExecutionException full){client.sending=false;disconnect(client.socket.getId());}
    }
    private void send(Client client){
        try{
            while(true){String message;
                synchronized(client){
                    if(clients.get(client.socket.getId())!=client)return;
                    message=client.privateMessages.pollFirst();
                    if(message==null&&!client.latest.isEmpty()){var it=client.latest.entrySet().iterator();message=it.next().getValue();it.remove();}
                    if(message==null){client.sending=false;return;}client.sendStarted=System.nanoTime();
                }
                client.socket.sendMessage(new TextMessage(message));metrics.add("ws.messages",1);metrics.add("ws.bytes",message.length());
                synchronized(client){client.sendStarted=0;}
            }
        }catch(IOException|RuntimeException error){disconnect(client.socket.getId());}
    }
    public void sweep(){
        for(Client client:clients.values())synchronized(client){
            if(!client.socket.isOpen()||client.sendStarted>0&&System.nanoTime()-client.sendStarted>TimeUnit.SECONDS.toNanos(2)){disconnect(client.socket.getId());continue;}
            if(client.user!=null&&client.authUntil<=System.currentTimeMillis()){
                remove(users,client.user,client.socket.getId());client.user=null;client.privateMessages.clear();
            }
        }
    }
    public void disconnect(String id){
        Client client=clients.remove(id);if(client==null)return;
        synchronized(client){
            if(client.user!=null)remove(users,client.user,id);
            for(String code:client.symbols)remove(symbols,code,id);
            client.privateMessages.clear();client.latest.clear();
        }
        metrics.gauge("ws.active",clients.size());
        market.marketViewerDisconnected(id);
        try{closers.execute(()->{try{client.socket.close(CloseStatus.SESSION_NOT_RELIABLE);}catch(IOException ignored){}});}catch(RejectedExecutionException ignored){}
    }
    private static <K> void remove(Map<K,Set<String>> map,K key,String id){map.computeIfPresent(key,(k,ids)->{ids.remove(id);return ids.isEmpty()?null:ids;});}
    @PreDestroy public void close() throws Exception{listener.close();for(String id:List.copyOf(clients.keySet()))disconnect(id);senders.shutdownNow();closers.shutdown();}
}
