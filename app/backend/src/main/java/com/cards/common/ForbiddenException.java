package com.cards.common;

/** The caller is signed in but may not do this (e.g. a host-only action). Becomes HTTP FORBIDDEN (403). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
