package com.cards.service.controller.dto;

import com.cards.common.Deck;

import java.util.List;
import java.util.UUID;

public record DeckResponse(UUID id, String name, String description, String visibility, int cardCount,
                           List<StatDefinitionResponse> stats) {

    public static DeckResponse from(Deck d) {
        return new DeckResponse(d.id(), d.name(), d.description(), d.visibility().name(),
                d.cardCount() == null ? 0 : d.cardCount(),
                d.stats().stream().map(StatDefinitionResponse::from).toList());
    }
}
