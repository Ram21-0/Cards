package com.cards.common;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.UUID;

/** One play-through with a deck. {@code joinCode} is what gets shared: link = <frontend>/join/<joinCode>. */
@Getter
@Builder
@Accessors(fluent = true)
public class Game {
    private final UUID id;
    private final String joinCode;
    private final UUID deckId;
    private final UUID hostId;                      // null = anonymous host
    private final GameMode mode;
    private final DifficultyLevel difficulty;       // CPU mode only, otherwise null
    private final GameStatus status;
    private final Integer rounds;
    private final Instant createdAt;
    private final Instant startedAt;
    private final Instant finishedAt;
}
