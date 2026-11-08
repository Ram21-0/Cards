package com.cards.common;

/** The caller is not signed in, or presented a bad credential. Becomes HTTP 401. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
