package com.cards.common;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A set of cards that can be played. Defines what its cards hold and how each stat is compared. */
@Getter
@Builder(toBuilder = true)
@Accessors(fluent = true)
public class Deck {
    private final UUID id;
    private final UUID ownerId;                     // null = built-in deck
    private final String name;
    private final String description;
    private final Visibility visibility;
    private final Integer cardCount;                // number of cards in the deck (null when not loaded)
    private final List<StatDefinition> stats;       // ordered by displayOrder
    private final Instant createdAt;
    private final Instant updatedAt;

    public StatDefinition statDefinition(String key) {
        return stats.stream()
                .filter(s -> s.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Deck '" + name + "' has no stat '" + key + "'"));
    }

    /** How card {@code card} shows its stat {@code key}, using this deck's display format. */
    public String display(Card card, String key) {
        return statDefinition(key).display(card.stat(key));
    }

    /** Outcome for the owner of {@code a} when facing {@code b} on stat {@code key}. */
    public Outcome compare(Card a, Card b, String key) {
        return statDefinition(key).compare(a.stat(key), b.stat(key));
    }
}
