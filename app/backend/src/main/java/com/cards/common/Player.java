package com.cards.common;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.UUID;

/**
 * A player account. {@code googleSub} is the stable Google account id (null for old username-only players and dev
 * logins use "dev:&lt;username&gt;"). {@code username} is chosen after the first sign-in, so it can be null until then.
 */
@Getter
@Builder
@Accessors(fluent = true)
public class Player {
    private final UUID id;
    private final String username;          // nullable
    private final String googleSub;         // nullable
    private final String email;             // nullable
    private final String displayName;       // nullable
    private final String avatarUrl;         // nullable
    private final Instant createdAt;

    public boolean needsUsername() {
        return username == null;
    }
}
