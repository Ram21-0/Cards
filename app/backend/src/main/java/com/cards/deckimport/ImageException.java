package com.cards.deckimport;

/** An uploaded image cannot be accepted. The message is fit to show to the user. */
public class ImageException extends RuntimeException {
    public ImageException(String message) {
        super(message);
    }
}
