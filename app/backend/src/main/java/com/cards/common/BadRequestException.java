package com.cards.common;

/** The request is invalid (bad input). Becomes HTTP 400. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
