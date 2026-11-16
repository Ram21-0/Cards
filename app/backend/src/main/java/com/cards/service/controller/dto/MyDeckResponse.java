package com.cards.service.controller.dto;

import com.cards.service.service.DeckLibraryService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One of my decks, with the state of its promotion request ("make it global") if there is one. */
public record MyDeckResponse(UUID id, String name, String description, String visibility, boolean live, int cardCount,
                             List<StatDefinitionResponse> stats, Promotion promotion) {

    /** {@code status}: PENDING, REJECTED or APPROVED_GLOBAL (then the deck is PUBLIC). {@code adminNote} explains a rejection. */
    public record Promotion(UUID requestId, String status, String adminNote, Instant createdAt, Instant decidedAt) {
    }

    public static MyDeckResponse from(DeckLibraryService.MyDeck mine) {
        var d = mine.deck();
        var p = mine.promotion();
        return new MyDeckResponse(d.id(), d.name(), d.description(), d.visibility().name(), d.playable(), d.cardCount() == null ? 0 : d.cardCount(),
                d.stats().stream().map(StatDefinitionResponse::from).toList(),
                p == null ? null : new Promotion(p.id(), p.status().name(), p.adminNote(), p.createdAt(), p.decidedAt()));
    }
}
