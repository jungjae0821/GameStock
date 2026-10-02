package com.gamestock.backend.market;

import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Presence comes from visitors/requests, never from bots, broadcasts or health probes. */
final class MarketActivityPolicy {
    private final Set<String> viewers=ConcurrentHashMap.newKeySet();
    private final AtomicLong lastActivity=new AtomicLong(Long.MIN_VALUE);
    private Boolean preparedActive;
    private long nextMaintenance, pulseUntil, epoch;
    private int pulseBatches;
    private final Map<String,Integer> attempts=new HashMap<>();
    void touch(long now){lastActivity.accumulateAndGet(now,Math::max);}
    void connected(String id,long now){viewers.add(id);touch(now);}
    void disconnected(String id,long now){if(viewers.remove(id))touch(now);}
    boolean active(long now,long graceMillis) {
        long last=lastActivity.get();
        return !viewers.isEmpty() || last!=Long.MIN_VALUE && now-last<graceMillis;
    }
    int viewers(){return viewers.size();}

    /** The callback includes COMMIT. Visitors cannot pass the wake barrier before expiry/refunds finish. */
    synchronized void maintain(long now,boolean active,long idleInterval,int batches,Runnable committedMaintenance) {
        boolean transition=preparedActive==null || preparedActive!=active;
        if(!transition && now<nextMaintenance)return;
        boolean pulse=!active&&!transition;
        committedMaintenance.run();
        if(transition||pulse){epoch++;attempts.clear();}
        preparedActive=active;
        pulseBatches=Math.max(1,Math.min(10,batches));
        pulseUntil=pulse?now+5000:0;
        // Never replay missed half-hour windows, even after a long process suspension.
        nextMaintenance=now+(active?3000:Math.max(60000,idleInterval));
    }
    synchronized boolean needsPreparation(boolean active) {return preparedActive==null||preparedActive!=active;}
    synchronized boolean maintenanceDue(long now,boolean active) {return needsPreparation(active)||now>=nextMaintenance;}
    synchronized boolean pulseOpen(long now) {return Boolean.FALSE.equals(preparedActive)&&now<pulseUntil;}
    synchronized boolean workDue(String worker,long now,boolean active) {
        return !needsPreparation(active) && (active || pulseOpen(now)&&attempts.getOrDefault(worker,0)<pulseBatches);
    }
    /** Quota counts attempts, including failed transactions, so a broken idle pulse cannot retry forever. */
    synchronized long claim(String worker,long now,boolean active) {
        if(!workDue(worker,now,active))return -1;
        if(!active)attempts.merge(worker,1,Integer::sum);
        return epoch;
    }
}
