package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public enum DifficultyLevel implements DbEnum {
    easy(0),
    medium(1),
    hard(2),
    extreme(3);

    private final int dbVal;

    public static DifficultyLevel get(int dbVal) {
        return Util.fromDbVal(DifficultyLevel.class, dbVal);
    }
}
