package com.cards.service.controller.dto;

import com.cards.common.Card;
import com.cards.common.Deck;
import com.cards.common.Stat;
import com.cards.common.StatDefinition;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A card as JSON. Each stat carries the raw {@code value} (for comparing) and the {@code displayValue}
 * (what to show on the card, formatted by the deck's stat definition), e.g.
 * {@code "height": {"value": 73, "displayValue": "6'1\""}}.
 */
public record CardResponse(Integer id, String name, String img, Map<String, StatValue> stats, Map<String, Object> meta) {

    public record StatValue(BigDecimal value, String displayValue) {
    }

    public static CardResponse from(Card c, Deck deck) {
        Map<String, Stat> byKey = new LinkedHashMap<>();
        c.stats().forEach(s -> byKey.put(s.key(), s));

        // In the deck's display order (jsonb does not keep key order). Stats the deck doesn't define are left out.
        Map<String, StatValue> stats = new LinkedHashMap<>();
        for (StatDefinition def : deck.stats()) {
            Stat s = byKey.get(def.key());
            if (s != null) stats.put(def.key(), new StatValue(s.value(), def.display(s)));
        }
        return new CardResponse(c.id(), c.name(), c.imageUrl(), stats, c.metadata());
    }
}
