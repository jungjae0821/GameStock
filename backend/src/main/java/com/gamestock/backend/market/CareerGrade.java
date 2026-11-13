package com.gamestock.backend.market;

/**
 * Prop desk grades, lowest first. Each grade carries the firm allocation it starts
 * from, the share of new gains paid out as bonus, the promotion bar and the loss line
 * that demotes. See docs/prop-career-design.md for how the numbers were chosen.
 */
public enum CareerGrade {
    TRAINEE("수습", 1_000_000L, 0.0, 0.0, 0, 0, 0, 0.0),
    STAFF("사원", 3_000_000L, 0.10, 0.10, 2, 3, 20, 0.20),
    ASSOCIATE("대리", 10_000_000L, 0.15, 0.10, 2, 5, 0, 0.20),
    MANAGER("과장", 30_000_000L, 0.20, 0.12, 3, 7, 0, 0.20),
    SENIOR_MANAGER("차장", 100_000_000L, 0.25, 0.12, 3, 10, 0, 0.15),
    GENERAL_MANAGER("부장", 300_000_000L, 0.30, 0.15, 3, 14, 0, 0.15),
    EXECUTIVE("이사", 1_000_000_000L, 0.40, 0.0, 0, 0, 0, 0.15);

    public final String label;
    public final long allocation;
    public final double bonusRate;
    /** Gain over the grade's base asset needed for promotion; 0 when promotion uses other steps. */
    public final double promotionReturn;
    /**
     * Daily settlements in a row that must close at or above the return target. One lucky
     * day is not enough: the gain has to be kept.
     */
    public final int promotionHold;
    public final int promotionTradingDays;
    public final int promotionFills;
    /** Loss from the grade's base asset that demotes; 0 when the grade cannot be demoted. */
    public final double demotionLoss;

    CareerGrade(String label, long allocation, double bonusRate, double promotionReturn, int promotionHold,
                int promotionTradingDays, int promotionFills, double demotionLoss) {
        this.label = label;
        this.allocation = allocation;
        this.bonusRate = bonusRate;
        this.promotionReturn = promotionReturn;
        this.promotionHold = promotionHold;
        this.promotionTradingDays = promotionTradingDays;
        this.promotionFills = promotionFills;
        this.demotionLoss = demotionLoss;
    }

    public CareerGrade next() { return this == EXECUTIVE ? null : values()[ordinal() + 1]; }

    public CareerGrade previous() { return this == TRAINEE ? null : values()[ordinal() - 1]; }

    public boolean atLeast(CareerGrade other) { return ordinal() >= other.ordinal(); }

    static CareerGrade parse(String value) {
        try {
            return value == null ? TRAINEE : valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return TRAINEE;
        }
    }
}
