package com.gamestock.backend.realtime;

import java.util.function.Consumer;

/** Replace the local adapter with Redis/NATS; preserve channel, eventId and serialized payload.
 * Adapters deliver both local and remote publications, without re-publishing received events.
 */
public interface EventFanout {
    record Envelope(String channel,String eventId,String json,String summaryJson){
        public Envelope(String channel,String eventId,String json){this(channel,eventId,json,json);}
    }
    void publish(Envelope event);
    AutoCloseable listen(Consumer<Envelope> listener);
    /** Distributed adapters publish changed symbols even when all viewers are on other replicas. */
    default boolean distributed(){return false;}
}
