package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

/** Lifecycle of a game. */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public enum GameStatus implements DbEnum {
    WAITING(0),
    IN_PROGRESS(1),
    FINISHED(2),
    ABANDONED(3);

    private final int dbVal;

    public static GameStatus get(int dbVal) {
        return Util.fromDbVal(GameStatus.class, dbVal);
    }
}
