package com.gamestock.backend.market;

public record MarketChangedEvent(MarketModels.MarketSnapshot snapshot) { }
