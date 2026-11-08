package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

/** Opponent type for a game. */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public enum GameMode implements DbEnum {
    CPU(0),
    PVP(1);

    private final int dbVal;

    public static GameMode get(int dbVal) {
        return Util.fromDbVal(GameMode.class, dbVal);
    }
}
