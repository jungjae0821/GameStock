package com.gamestock.backend.market;

import static com.gamestock.backend.market.MarketModels.*;

public final class OrderBatch {
    private OrderBatch(){}
    public record Command(long userId,OrderRequest request){}
    public record Outcome(OrderResult result,String error){
        static Outcome rejected(String error){return new Outcome(null,error);}
    }
}
