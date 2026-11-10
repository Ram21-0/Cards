package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

/** Who occupies a seat in a game. */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public enum SeatKind implements DbEnum {
    HUMAN(0),
    CPU(1);

    private final int dbVal;

    public static SeatKind get(int dbVal) {
        return Util.fromDbVal(SeatKind.class, dbVal);
    }
}
