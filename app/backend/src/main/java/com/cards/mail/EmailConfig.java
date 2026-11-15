package com.cards.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Log-only unless both EMAIL_API_KEY and EMAIL_FROM are set, then Resend. Nothing is ever sent from tests or local development by accident. */
@Configuration
public class EmailConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    @Bean
    public EmailSender emailSender(ObjectMapper json, @Value("${app.email.api-key:}") String apiKey, @Value("${app.email.from:}") String from) {
        if (apiKey.isBlank() || from.isBlank()) {
            log.info("Email: EMAIL_API_KEY / EMAIL_FROM not set, emails are only logged");
            return new LogEmailSender();
        }
        log.info("Email: sending through the Resend API");
        return ResendEmailSender.create(json, apiKey, from);
    }
}
