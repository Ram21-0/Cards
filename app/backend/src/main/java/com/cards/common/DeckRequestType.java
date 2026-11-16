package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

/** CATEGORY: a suggested category (the admin makes the deck). PROMOTE: the owner of a private deck asks to make it global. */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public enum DeckRequestType implements DbEnum {
    CATEGORY(0),
    PROMOTE(1);

    private final int dbVal;

    public static DeckRequestType get(int dbVal) {
        return Util.fromDbVal(DeckRequestType.class, dbVal);
    }
}
