package com.gamestock.backend.market;

/**
 * Prop desk grades, lowest first. Each grade carries the firm allocation it starts
 * from, the share of new gains paid out as bonus, what promotion asks for and the loss
 * line that demotes. See docs/prop-career-design.md for how the numbers were chosen.
 *
 * <p>Staff are promoted on activity: trading days, a fill made on fresh news, no daily
 * loss-limit breach and a confirmed return no worse than {@link #STAFF_RETURN_FLOOR}.
 * From associate up, promotion takes weekly ranking points: each Korean week the members
 * of a grade are ranked by their return over the game index divided by their account's
 * daily volatility, and the top share with a positive excess earn a point. Ranking against
 * the index and against each other keeps the pace the same in a trending month and a quiet
 * one; dividing by volatility keeps a lucky all-in from outranking steady trading.
 */
public enum CareerGrade {
    TRAINEE("수습", 1_000_000L, 0.0, 0, 0, 0, 0.0),
    STAFF("사원", 3_000_000L, 0.10, 0, 3, 1, 0.20),
    ASSOCIATE("대리", 10_000_000L, 0.15, 1, 0, 0, 0.20),
    MANAGER("과장", 30_000_000L, 0.20, 2, 0, 0, 0.20),
    SENIOR_MANAGER("차장", 100_000_000L, 0.25, 2, 0, 0, 0.15),
    GENERAL_MANAGER("부장", 300_000_000L, 0.30, 3, 0, 0, 0.15),
    EXECUTIVE("이사", 1_000_000_000L, 0.40, 0, 0, 0, 0.15);

    /** A staff member may not be down more than this on the grade's base, in confirmed terms, to be promoted. */
    public static final double STAFF_RETURN_FLOOR = -0.05;

    public final String label;
    public final long allocation;
    public final double bonusRate;
    /** Weekly ranking points needed for promotion; 0 when promotion uses other steps. */
    public final int promotionPoints;
    public final int promotionTradingDays;
    /** Fills made within two hours of news on the same stock. */
    public final int promotionNewsFills;
    /** Loss from the grade's base asset that demotes; 0 when the grade cannot be demoted. */
    public final double demotionLoss;

    CareerGrade(String label, long allocation, double bonusRate, int promotionPoints, int promotionTradingDays,
                int promotionNewsFills, double demotionLoss) {
        this.label = label;
        this.allocation = allocation;
        this.bonusRate = bonusRate;
        this.promotionPoints = promotionPoints;
        this.promotionTradingDays = promotionTradingDays;
        this.promotionNewsFills = promotionNewsFills;
        this.demotionLoss = demotionLoss;
    }

    /** Grades promoted by weekly ranking points. */
    public boolean ranked() { return promotionPoints > 0; }

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
