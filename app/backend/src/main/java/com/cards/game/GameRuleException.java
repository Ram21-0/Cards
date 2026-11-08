package com.cards.game;

/** A command that is not allowed right now (wrong seat, wrong phase, unknown card or stat, game over). */
public class GameRuleException extends RuntimeException {
    public GameRuleException(String message) {
        super(message);
    }
}
