package com.cards.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

/** Who can see/use a deck. PUBLIC = listed for everyone, UNLISTED = anyone with the link, PRIVATE = owner only. */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public enum Visibility implements DbEnum {
    PRIVATE(0),
    UNLISTED(1),
    PUBLIC(2);

    private final int dbVal;

    public static Visibility get(int dbVal) {
        return Util.fromDbVal(Visibility.class, dbVal);
    }
}
