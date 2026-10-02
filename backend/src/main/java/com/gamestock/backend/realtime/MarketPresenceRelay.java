package com.gamestock.backend.realtime;

import com.gamestock.backend.market.*;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Remote presence is a renewable activity pulse, so a crashed replica cannot keep bots awake forever. */
@Component
public class MarketPresenceRelay {
    private final EventFanout fanout;
    private final ConnectionManager connections;
    private final MarketService market;
    private final AutoCloseable listener;
    private final AtomicLong last=new AtomicLong();
    public MarketPresenceRelay(EventFanout fanout,ConnectionManager connections,MarketService market){
        this.fanout=fanout;this.connections=connections;this.market=market;
        listener=fanout.listen(event->{if(event.channel().equals("presence:market")&&market.matchingEnabled())market.recordRemoteActivity();});
    }
    @EventListener public void active(MarketPresenceEvent ignored){pulse();}
    @Scheduled(fixedDelay=30000) public void heartbeat(){if(connections.size()>0)pulse();}
    private void pulse(){
        if(!fanout.distributed())return;
        long before=last.get(),now=System.currentTimeMillis();
        if(now-before<25000||!last.compareAndSet(before,now))return;
        fanout.publish(new EventFanout.Envelope("presence:market",UUID.randomUUID().toString(),"{}"));
    }
    @PreDestroy public void close()throws Exception{listener.close();}
}
