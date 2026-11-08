package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

/** Kind of number a stat holds (deck_stats.value_type). */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public enum ValueType implements DbEnum {
    INT(0),
    DECIMAL(1);

    private final int dbVal;

    public static ValueType get(int dbVal) {
        return Util.fromDbVal(ValueType.class, dbVal);
    }
}
