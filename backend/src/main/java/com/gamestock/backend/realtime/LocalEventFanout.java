package com.gamestock.backend.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class LocalEventFanout implements EventFanout {
    private final CopyOnWriteArrayList<Consumer<Envelope>> listeners=new CopyOnWriteArrayList<>();
    public void publish(Envelope event){for(var listener:listeners)listener.accept(event);}
    public AutoCloseable listen(Consumer<Envelope> listener){listeners.add(listener);return ()->listeners.remove(listener);}
    @Configuration
    static class AdapterConfiguration {
        @Bean @ConditionalOnMissingBean(EventFanout.class)
        EventFanout eventFanout(){return new LocalEventFanout();}
    }
}
