package com.cards.common;

import java.util.UUID;

/**
 * A seat as shown in a lobby: who sits there (username for humans, difficulty for CPUs). {@code playerId} is internal
 * (who the human is; null for CPUs) and is never put in an API response; {@code placement} is set once the game ended.
 */
public record SeatInfo(int seat, SeatKind kind, String username, DifficultyLevel difficulty, UUID playerId, Integer placement) {

    public SeatInfo(int seat, SeatKind kind, String username, DifficultyLevel difficulty) {
        this(seat, kind, username, difficulty, null, null);
    }

    public boolean isCpu() {
        return kind == SeatKind.CPU;
    }
}
