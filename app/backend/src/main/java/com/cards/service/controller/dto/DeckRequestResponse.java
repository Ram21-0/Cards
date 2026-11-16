package com.cards.service.controller.dto;

import com.cards.common.DeckRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A request as its requester sees it. {@code live} = the deck has been published; {@code deckId} is set once it is. */
public record DeckRequestResponse(UUID id, String type, String category, String description, List<String> names, String status, String adminNote,
                                  Instant createdAt, Instant decidedAt, boolean live, UUID deckId) {

    public static DeckRequestResponse from(DeckRequest r) {
        boolean live = r.publishedAt() != null;
        boolean promotion = r.type() == com.cards.common.DeckRequestType.PROMOTE;
        return new DeckRequestResponse(r.id(), r.type().name(), r.category(), r.description(), r.names(), r.status().name(), r.adminNote(),
                r.createdAt(), r.decidedAt(), live, promotion || live ? r.resultingDeckId() : null);
    }
}
