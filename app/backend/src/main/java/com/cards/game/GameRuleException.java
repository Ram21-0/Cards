package com.cards.game;

/** A command that is not allowed right now (wrong seat, wrong phase, unknown card or stat, game over). */
public class GameRuleException extends RuntimeException {

    /** Why it was refused; the REST layer maps it to 400 / 403 / 409. */
    public enum Kind {
        /** The input is wrong whatever the state: unknown stat, a card the seat does not hold, no such seat. */
        BAD_INPUT,
        /** The input is fine but this seat may not act now. */
        NOT_YOUR_TURN,
        /** The game is not waiting for this kind of command. */
        WRONG_PHASE,
        GAME_OVER
    }

    private final Kind kind;

    public GameRuleException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
