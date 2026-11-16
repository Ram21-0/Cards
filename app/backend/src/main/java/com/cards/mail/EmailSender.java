package com.cards.mail;

/** Sends an email. Implementations may throw on failure; callers decide what a failure means (for deck requests: log it and move on). */
public interface EmailSender {

    void send(Email email) throws EmailException;

    /** A message that could not be sent. Never carries secrets in its message. */
    class EmailException extends Exception {
        public EmailException(String message) {
            super(message);
        }

        public EmailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
