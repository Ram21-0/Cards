package com.cards.common;

/** The request conflicts with existing data (e.g. a taken username). Becomes HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
