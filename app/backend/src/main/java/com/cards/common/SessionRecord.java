package com.cards.common;

import java.time.Instant;
import java.util.UUID;

/** A stored session (the token itself is never stored, only its hash). */
public record SessionRecord(String tokenHash, UUID playerId, Instant expiresAt, Instant lastSeen) {
}
