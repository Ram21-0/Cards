package com.cards.service.controller.dto;

import java.util.UUID;

public record CreateGameRequest(UUID deckId, Integer maxPlayers, UUID hostId) {
}
