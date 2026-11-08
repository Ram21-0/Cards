package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public class Player {
    private final UUID id;
    private final String username;
    private final Instant createdAt;
}
