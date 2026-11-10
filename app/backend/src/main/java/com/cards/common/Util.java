package com.cards.common;

public final class Util {

    private Util() {
    }

    /** Looks up the constant whose database code is {@code dbVal}. */
    public static <E extends Enum<E> & DbEnum> E fromDbVal(Class<E> type, int dbVal) {
        for (E e : type.getEnumConstants()) {
            if (e.dbVal() == dbVal) return e;
        }
        throw new IllegalArgumentException("No " + type.getSimpleName() + " with dbVal " + dbVal);
    }
}
