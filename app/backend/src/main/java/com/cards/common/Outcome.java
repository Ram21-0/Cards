package com.cards.common;

/** Result of comparing two values in one round, from the first card's point of view. Not stored in the DB. */
public enum Outcome {
    WIN, LOSS, TIE;

    /** Positive = WIN, negative = LOSS, zero = TIE (the sign of a compareTo result). */
    public static Outcome of(int cmp) {
        return cmp > 0 ? WIN : cmp < 0 ? LOSS : TIE;
    }

    public Outcome opposite() {
        return this == WIN ? LOSS : this == LOSS ? WIN : TIE;
    }
}
