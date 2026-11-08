package com.cards.common;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
@Accessors(fluent = true)
public class Card {
    private final Integer id;                       // null before it is saved
    private final UUID deckId;
    private final String name;
    private final String imageUrl;                  // nullable
    private final List<Stat> stats;
    private final Map<String, Object> metadata;     // display-only extras, e.g. {"brand": "raw"}

    public Stat stat(String key) {
        return stats.stream()
                .filter(s -> s.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Card '" + name + "' has no stat '" + key + "'"));
    }
}
