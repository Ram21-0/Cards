package com.cards.common;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A user's request for a new deck category, with the names they suggest. */
@Getter
@Builder(toBuilder = true)
@Accessors(fluent = true)
public class DeckRequest {
    private final UUID id;
    private final UUID requesterId;
    private final String requesterUsername;         // joined from players (null when not loaded or the account was deleted)
    private final String category;
    private final String description;               // nullable
    private final List<String> names;
    private final DeckRequestStatus status;
    private final String adminNote;                 // nullable
    private final UUID resultingDeckId;             // nullable until approved
    private final Instant createdAt;
    private final Instant decidedAt;
    private final UUID decidedBy;
    private final Instant notifiedAt;
    private final Instant publishedAt;              // set when the deck went live
    private final int cardCount;                    // cards imported into the resulting deck so far
}
