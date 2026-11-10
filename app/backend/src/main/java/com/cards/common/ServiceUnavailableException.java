package com.cards.common;

/** A needed dependency is missing or unreachable (e.g. Google sign-in not configured). Becomes HTTP 503. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
