package com.cards.deckimport;

/** Cleaning of user-supplied text: one line, no control characters, no runs of whitespace. */
public final class Text {

    private Text() {
    }

    /** Control characters out (a value is one line), runs of whitespace become one space, trimmed. Null becomes "". */
    public static String clean(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replaceAll("\\s+", " ").trim();
    }
}
