package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

/** Where a deck request is. The two APPROVED_* values record what the admin decided; whether the deck is live is {@code publishedAt}. */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public enum DeckRequestStatus implements DbEnum {
    PENDING(0),
    REJECTED(1),
    APPROVED_PRIVATE(2),
    APPROVED_GLOBAL(3);

    private final int dbVal;

    public static DeckRequestStatus get(int dbVal) {
        return Util.fromDbVal(DeckRequestStatus.class, dbVal);
    }

    public boolean approved() {
        return this == APPROVED_PRIVATE || this == APPROVED_GLOBAL;
    }
}
