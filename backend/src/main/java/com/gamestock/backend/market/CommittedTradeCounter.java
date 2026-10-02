package com.gamestock.backend.market;

import java.util.*;

/** A bounded rolling window of durable fills, never submitted orders. Owned by one locked lane. */
final class CommittedTradeCounter {
    private final Map<Long,NavigableMap<Long,Integer>> byStock=new HashMap<>();
    void add(long stock,long at,int count) {
        if(count>0)byStock.computeIfAbsent(stock,k->new TreeMap<>()).merge(at,count,Integer::sum);
    }
    Map<Long,Integer> recent(long now) {
        Map<Long,Integer> result=new HashMap<>();
        byStock.forEach((stock,window)->{
            window.headMap(now-1000,false).clear();
            result.put(stock,window.headMap(now,true).values().stream().mapToInt(Integer::intValue).sum());
        });
        return result;
    }
}
