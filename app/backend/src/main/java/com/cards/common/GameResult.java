package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

/** A player's result for a finished game (game_players.result). */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public enum GameResult implements DbEnum {
    WIN(0),
    LOSS(1),
    DRAW(2);

    private final int dbVal;

    public static GameResult get(int dbVal) {
        return Util.fromDbVal(GameResult.class, dbVal);
    }
}
