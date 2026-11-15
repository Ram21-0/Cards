package com.cards.game;

/**
 * Where a game is (RULES.md §9). The AWAIT_* phases wait for a player (or for time to pass); RESOLVE, CLASH_RESOLVE and
 * ROUND_DONE are transient — the engine enters and leaves them inside a single command, so callers never observe them.
 * ROUND_RESULT is observable: a timed pause between rounds.
 */
public enum Phase {
    AWAIT_CHOOSE_CALLER,
    AWAIT_CALL,
    AWAIT_CHOOSE_OTHERS,
    RESOLVE,
    CLASH_AWAIT_CALL,
    CLASH_AWAIT_CHOOSE,
    CLASH_RESOLVE,
    ROUND_DONE,
    /** The round is decided and its result is on show; the next round starts when the deadline passes (RULES.md §2 step 8). Nobody acts. */
    ROUND_RESULT,
    GAME_OVER
}
