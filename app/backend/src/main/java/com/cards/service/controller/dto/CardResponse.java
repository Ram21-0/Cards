package com.cards.service.controller.dto;

import com.cards.common.Card;
import com.cards.common.Stat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record CardResponse(Integer id, String name, String img, Map<String, BigDecimal> stats, Map<String, Object> meta) {

    public static CardResponse from(Card c) {
        Map<String, BigDecimal> stats = new LinkedHashMap<>();
        for (Stat s : c.stats()) stats.put(s.key(), s.value());
        return new CardResponse(c.id(), c.name(), c.imageUrl(), stats, c.metadata());
    }
}
