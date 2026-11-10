package com.cards.common;

import java.math.BigDecimal;

/**
 * How two values of a stat are compared (stored on deck_stats.comparison_strategy).
 * A stat with a genuinely different rule gets a new constant here, plus a new code in the DB check constraint.
 */
public enum ComparisonStrategy implements DbEnum {

    HIGHER_WINS(0) {
        @Override
        public Outcome compare(BigDecimal a, BigDecimal b) {
            return Outcome.of(a.compareTo(b));
        }
    },
    LOWER_WINS(1) {
        @Override
        public Outcome compare(BigDecimal a, BigDecimal b) {
            return Outcome.of(b.compareTo(a));
        }
    };

    private final int dbVal;

    ComparisonStrategy(int dbVal) {
        this.dbVal = dbVal;
    }

    @Override
    public int dbVal() {
        return dbVal;
    }

    /** Outcome for the owner of {@code a} when facing {@code b}. */
    public abstract Outcome compare(BigDecimal a, BigDecimal b);

    public static ComparisonStrategy get(int dbVal) {
        return Util.fromDbVal(ComparisonStrategy.class, dbVal);
    }
}
