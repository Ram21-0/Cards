package com.cards.service.controller.dto;

import com.cards.common.DeckRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A request as the admin sees it: who asked (username only), who decided and whether the mail went out. */
public record AdminDeckRequestResponse(UUID id, String type, Requester requester, String category, String description, List<String> names, String status,
                                       String adminNote, UUID deckId, Instant createdAt, Instant decidedAt, UUID decidedBy,
                                       Instant notifiedAt, boolean live, Instant publishedAt, int cardCount) {

    public record Requester(UUID id, String username) {
    }

    public static AdminDeckRequestResponse from(DeckRequest r) {
        return new AdminDeckRequestResponse(r.id(), r.type().name(), new Requester(r.requesterId(), r.requesterUsername()), r.category(), r.description(), r.names(),
                r.status().name(), r.adminNote(), r.resultingDeckId(), r.createdAt(), r.decidedAt(), r.decidedBy(), r.notifiedAt(),
                r.publishedAt() != null, r.publishedAt(), r.cardCount());
    }
}
