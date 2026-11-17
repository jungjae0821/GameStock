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
    private com.zaxxer.hikari.HikariDataSource pool;

    /** DB 연결 풀 상태도 함께 남긴다. 연결을 기다리는 요청(db.pool.waiting)이 쌓이면 풀이 바닥난 것이다. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configurePool(javax.sql.DataSource source) {
        if (source instanceof com.zaxxer.hikari.HikariDataSource hikari) pool = hikari;
    }

    private void samplePool() {
        // 풀은 첫 연결 때 만들어지므로 그 전에는 비어 있다.
        var current = pool == null ? null : pool.getHikariPoolMXBean();
        if (current == null) return;
        try {
            gauge("db.pool.active", current.getActiveConnections());
            gauge("db.pool.idle", current.getIdleConnections());
            gauge("db.pool.total", current.getTotalConnections());
            gauge("db.pool.waiting", current.getThreadsAwaitingConnection());
        } catch (RuntimeException closing) {
            // 종료 중에는 풀 정보를 읽지 못할 수 있다.
        }
    }

    public Map<String,Long> snapshot(){
        samplePool();
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
