package com.gamestock.backend.market;

/** Keep price-time priority until the thesis changes, a meaningful repricing is needed, or TTL expires. */
public final class BotOrderPolicy {
    public record RestingOrder(long id, String side, long price, long cash, long createdAt) {}

    public boolean shouldReplace(BotProfile profile, RestingOrder order, BotStrategyEngine.Decision decision,
                                 long now, long tick, double bestSameSidePrice) {
        return shouldReplace(profile,order,decision,now,tick,bestSameSidePrice,
                "BUY".equals(order.side())?Double.POSITIVE_INFINITY:0);
    }

    public boolean shouldReplace(BotProfile profile, RestingOrder order, BotStrategyEngine.Decision decision,
                                 long now, long tick, double bestSameSidePrice, double bestOppositePrice) {
        double directedScore = "BUY".equals(order.side()) ? decision.score() : -decision.score();
        if (directedScore < -profile.confidenceThreshold() * 1.5) return true;
        if (!order.side().equals(decision.side()) || decision.quantity() == 0) return false;
        boolean patient = profile.strategy().family() == BotProfile.Family.VALUE
                || profile.strategy().family() == BotProfile.Family.SWING;
        long minimumAge = patient ? 15_000 : 2_000;
        boolean executable=decision.market() || ("BUY".equals(order.side())
                ? decision.limitPrice()>=bestOppositePrice : decision.limitPrice()<=bestOppositePrice);
        double ageFloor=Math.max(profile.decisionInterval()<1000?200:0,minimumAge/profile.cancelSpeed());
        if(executable && now-order.createdAt()>=ageFloor
                && (decision.market() || Math.abs(order.price()-decision.limitPrice())>=tick)) return true;
        double distance = Math.max(tick * (patient ? 4 : 2), order.price() * (patient ? .004 : .001));
        double lostPricePriority="BUY".equals(order.side())?bestSameSidePrice-order.price():order.price()-bestSameSidePrice;
        double priceFloor=Math.max(profile.decisionInterval()<1000?tick*2:0,distance/profile.cancelSpeed());
        return now - order.createdAt() >= ageFloor
                && lostPricePriority >= priceFloor
                && Math.abs(order.price() - decision.limitPrice()) >= priceFloor;
    }
}
