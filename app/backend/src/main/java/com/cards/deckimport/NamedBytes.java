package com.cards.deckimport;

/** An uploaded file: its name as the browser sent it, and its content. */
public record NamedBytes(String name, byte[] bytes) {
}
