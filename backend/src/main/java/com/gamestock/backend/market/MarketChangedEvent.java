package com.gamestock.backend.market;

/** Frozen public state from matching, or an invalidation for legacy/maintenance updates. */
public record MarketChangedEvent(java.util.Map<String,Object> symbols) {
    public MarketChangedEvent(){this(java.util.Map.of());}
    public MarketChangedEvent{symbols=java.util.Map.copyOf(symbols);}
}
