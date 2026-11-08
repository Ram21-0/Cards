package com.cards.common;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.UUID;

/** A stat that every card in a deck holds, and how it is compared (a row of deck_stats). */
@Getter
@Builder
@Accessors(fluent = true)
public class StatDefinition {
    private final Integer id;               // null before it is saved
    private final UUID deckId;
    private final String key;               // key inside Card stats, e.g. "height"
    private final String label;             // shown on the card
    private final String unit;              // nullable
    private final ValueType valueType;
    private final ComparisonStrategy comparisonStrategy;
    private final DisplayFormat displayFormat;
    private final int displayOrder;

    /** The value as the card should show it, e.g. 73 -> 6'1" (see {@link DisplayFormat}). */
    public String display(Stat stat) {
        return displayFormat.format(stat.value(), unit);
    }

    /** Outcome for the owner of {@code a} when facing {@code b}, using this stat's strategy. */
    public Outcome compare(Stat a, Stat b) {
        if (!key.equals(a.key()) || !key.equals(b.key())) {
            throw new IllegalArgumentException("Stats " + a.key() + "/" + b.key() + " are not '" + key + "'");
        }
        return comparisonStrategy.compare(a.value(), b.value());
    }
}
