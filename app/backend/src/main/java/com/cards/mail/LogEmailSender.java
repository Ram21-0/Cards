package com.cards.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The default: writes the mail to the log instead of sending it (local development, tests). */
public class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void send(Email email) {
        log.info("EMAIL (log only, not sent) to={} subject={}\n{}", email.to(), email.subject(), email.text());
    }
}
