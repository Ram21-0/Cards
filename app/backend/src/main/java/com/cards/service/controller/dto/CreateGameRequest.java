package com.cards.service.controller.dto;

import java.util.UUID;

/** The host is always the signed-in player, so there is no hostId (an old client that still sends one is ignored). */
public record CreateGameRequest(UUID deckId, Integer maxPlayers) {
}
