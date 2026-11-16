package com.cards.deckimport;

import com.cards.common.DisplayFormat;
import com.cards.common.StatDefinition;
import com.cards.common.ValueType;

import java.math.BigDecimal;

/**
 * One stat of an import: which CSV {@code column} holds it and how the deck defines it. {@code valueType} null = found from the data (whole
 * numbers everywhere = INT, else DECIMAL). {@code min}/{@code max} bound every cell; {@code maxDecimals} is at most 2.
 */
public record StatSpec(String column, String key, String label, String unit, boolean higherWins, ValueType valueType, DisplayFormat displayFormat,
                       BigDecimal min, BigDecimal max, int maxDecimals) {

    public static final BigDecimal USER_MIN = BigDecimal.valueOf(-1_000_000);
    public static final BigDecimal USER_MAX = BigDecimal.valueOf(1_000_000);

    /** The stats of the built-in template deck (Wrestling), with the ranges that make sense for each of its keys. */
    public static StatSpec fromDefinition(StatDefinition def) {
        BigDecimal[] range = switch (def.key()) {
            case "rank" -> new BigDecimal[]{BigDecimal.ONE, BigDecimal.valueOf(10_000)};
            case "fight" -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.valueOf(1_000)};
            case "chest" -> new BigDecimal[]{BigDecimal.ONE, BigDecimal.valueOf(100)};
            case "biceps" -> new BigDecimal[]{BigDecimal.ONE, BigDecimal.valueOf(60)};
            case "height" -> new BigDecimal[]{BigDecimal.valueOf(24), BigDecimal.valueOf(120)};
            case "weight" -> new BigDecimal[]{BigDecimal.ONE, BigDecimal.valueOf(1_500)};
            default -> new BigDecimal[]{USER_MIN, USER_MAX};
        };
        return new StatSpec(def.key(), def.key(), def.label(), def.unit(), def.comparisonStrategy() == com.cards.common.ComparisonStrategy.HIGHER_WINS,
                def.valueType(), def.displayFormat(), range[0], range[1], 2);
    }
}
