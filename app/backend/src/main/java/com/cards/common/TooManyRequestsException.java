package com.cards.common;

/** The caller is acting faster than allowed. Becomes HTTP TOO MANY REQUESTS (429). */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
