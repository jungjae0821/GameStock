package com.gamestock.backend.market;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

/** Cumulative counters and gauges; no database queries or per-order logging. */
@Component
public class MarketMetrics {
    private final Map<String,LongAdder> counters=new ConcurrentHashMap<>();
    private final Map<String,AtomicLong> gauges=new ConcurrentHashMap<>();
    public void add(String name,long count){counters.computeIfAbsent(name,k->new LongAdder()).add(count);}
    public void gauge(String name,long value){gauges.computeIfAbsent(name,k->new AtomicLong()).set(value);}
    public void timing(String name,long nanos){add(name+".count",1);add(name+".nanos",nanos);}
    public Map<String,Long> snapshot(){
        Map<String,Long> result=new TreeMap<>();counters.forEach((k,v)->result.put(k,v.sum()));gauges.forEach((k,v)->result.put(k,v.get()));return result;
    }
    private Map<String,Long> previous=Map.of();
    private long last=System.nanoTime();
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void log(){
        long now=System.nanoTime();double seconds=(now-last)/1e9;var current=snapshot();
        if(!current.equals(previous)) LoggerFactory.getLogger(getClass()).info(
            "Market humanOrders/s={} botOrders/s={} websocketMessages/s={} metrics={}",
            rate(current,"human.orders",seconds),rate(current,"bot.orders",seconds),rate(current,"ws.messages",seconds),current);
        previous=current;last=now;
    }
    private double rate(Map<String,Long> current,String key,double seconds){return (current.getOrDefault(key,0L)-previous.getOrDefault(key,0L))/seconds;}
}
