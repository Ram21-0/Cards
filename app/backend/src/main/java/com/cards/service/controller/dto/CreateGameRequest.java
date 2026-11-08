package com.cards.service.controller.dto;

import com.cards.service.service.GameOptions;

import java.util.UUID;

/**
 * The host is always the signed-in player, so there is no hostId (an old client that still sends one is ignored).
 * {@code options} is optional, and so is every field in it; missing ones keep the server defaults.
 */
public record CreateGameRequest(UUID deckId, Integer maxPlayers, Options options) {

    public record Options(Integer maxRounds, Integer chooseThreshold, Integer clashCallSeconds, Integer turnTimeoutSeconds) {
    }

    public GameOptions gameOptions() {
        return options == null ? GameOptions.NONE
                : new GameOptions(options.maxRounds(), options.chooseThreshold(), options.clashCallSeconds(), options.turnTimeoutSeconds());
    }
}
