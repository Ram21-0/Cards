package com.cards.common;

/** Something that was asked for does not exist (or is not visible to the caller). Becomes HTTP 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
