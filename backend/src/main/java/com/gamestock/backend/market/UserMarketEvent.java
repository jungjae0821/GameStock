package com.gamestock.backend.market;

/** Only delivered after commit; user IDs are derived from authenticated server-side accounts. */
public record UserMarketEvent(long userId,String type,Object payload,long sequence){
    private static final java.util.concurrent.atomic.AtomicLong NEXT=new java.util.concurrent.atomic.AtomicLong();
    public UserMarketEvent(long userId,String type,Object payload){this(userId,type,payload,NEXT.incrementAndGet());}
}
