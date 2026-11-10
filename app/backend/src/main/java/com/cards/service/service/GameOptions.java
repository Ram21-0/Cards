package com.cards.service.service;

/**
 * What the host may override when creating a game (RULES.md §1). A null field keeps the default from application.yml.
 * The ranges are checked by {@link com.cards.game.GameSettings}.
 */
public record GameOptions(Integer maxRounds, Integer chooseThreshold, Integer clashCallSeconds, Integer turnTimeoutSeconds) {

    public static final GameOptions NONE = new GameOptions(null, null, null, null);
}
