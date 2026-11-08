package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.UUID;

/** A player's seat in a game (1 human in CPU mode, 2 in PVP). */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public class GamePlayer {
    private final UUID gameId;
    private final UUID playerId;
    private final int seat;                         // 1 or 2
    private final GameResult result;                // null until the game finishes
    private final Instant joinedAt;
}
