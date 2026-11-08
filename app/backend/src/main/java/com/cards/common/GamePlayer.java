package com.cards.common;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.UUID;

/** A seat in a game: a human (playerId set) or a CPU (playerId null, difficulty set). */
@Getter
@Builder
@Accessors(fluent = true)
public class GamePlayer {
    private final UUID gameId;
    private final int seat;                         // 1..maxPlayers
    private final SeatKind kind;
    private final UUID playerId;                    // HUMAN only
    private final DifficultyLevel difficulty;       // CPU only
    private final Integer placement;                // 1 = winner; null until decided; ties share a number
    private final Instant joinedAt;

    public boolean isCpu() {
        return kind == SeatKind.CPU;
    }
}
