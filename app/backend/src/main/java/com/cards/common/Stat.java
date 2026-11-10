package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/** One card's value for one stat, e.g. key "height", value 73. What the stat means is in {@link StatDefinition}. */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public class Stat {
    private final String key;
    private final BigDecimal value;
}
