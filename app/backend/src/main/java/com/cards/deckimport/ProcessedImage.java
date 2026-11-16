package com.cards.deckimport;

/** An uploaded image after it has been decoded, scaled and re-encoded by us (so nothing of the original file survives). */
public record ProcessedImage(byte[] bytes, String contentType, int width, int height) {
}
