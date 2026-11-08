package com.cards.common;

import java.math.BigDecimal;

/**
 * How a stat value is rendered as text for the card (deck_stats.display_format).
 * The API sends this as {@code displayValue}, so clients never need to know about feet/inches, units, etc.
 */
public enum DisplayFormat implements DbEnum {

    /** 38.50 -> "38.5", 15.0 -> "15" */
    PLAIN(0) {
        @Override
        public String format(BigDecimal value, String unit) {
            return plain(value);
        }
    },
    /** Value is TOTAL INCHES: 73 -> 6'1" */
    FEET_INCHES(1) {
        @Override
        public String format(BigDecimal value, String unit) {
            BigDecimal feet = value.divideToIntegralValue(BigDecimal.valueOf(12));
            BigDecimal inches = value.subtract(feet.multiply(BigDecimal.valueOf(12)));
            return plain(feet) + "'" + plain(inches) + "\"";
        }
    },
    /** Value followed directly by the unit: 240 + "lbs" -> "240lbs" (just the value when there is no unit). */
    WITH_UNIT(2) {
        @Override
        public String format(BigDecimal value, String unit) {
            return plain(value) + (unit == null ? "" : unit);
        }
    };

    private final int dbVal;

    DisplayFormat(int dbVal) {
        this.dbVal = dbVal;
    }

    @Override
    public int dbVal() {
        return dbVal;
    }

    public abstract String format(BigDecimal value, String unit);

    public static DisplayFormat get(int dbVal) {
        return Util.fromDbVal(DisplayFormat.class, dbVal);
    }

    private static String plain(BigDecimal v) {
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
