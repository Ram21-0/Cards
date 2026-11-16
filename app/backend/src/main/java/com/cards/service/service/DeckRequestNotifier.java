package com.cards.service.service;

import com.cards.common.DeckRequest;
import com.cards.mail.Email;
import com.cards.mail.EmailSender;
import com.cards.service.db.DeckRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Mails the admin(s) when a deck request is created. Runs on its own thread so the user's request is never slowed down, and a failure
 * (no provider, bad key, network) is only logged, never thrown. Everything user-supplied is HTML-escaped in the mail.
 */
@Component
public class DeckRequestNotifier {

    private static final Logger log = LoggerFactory.getLogger(DeckRequestNotifier.class);

    private final EmailSender sender;
    private final DeckRequestRepository requests;
    private final Executor executor;
    private final Clock clock;
    private final List<String> recipients;
    private final String frontendBaseUrl;

    public DeckRequestNotifier(EmailSender sender, DeckRequestRepository requests, @Qualifier("mailExecutor") Executor executor, Clock clock,
                               @Value("${app.admin.notify-to:}") String notifyTo, @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.sender = sender;
        this.requests = requests;
        this.executor = executor;
        this.clock = clock;
        this.recipients = Arrays.stream(notifyTo.split("[,;\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/") ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
    }

    /** Fire and forget. */
    public void created(DeckRequest request) {
        if (recipients.isEmpty()) {
            log.warn("A deck request was created but no admin address is configured (ADMIN_NOTIFY_TO / ADMIN_EMAILS): no email");
            return;
        }
        try {
            executor.execute(() -> send(request));
        } catch (RuntimeException e) {
            log.warn("Could not queue the deck request email: {}", e.getClass().getSimpleName());
        }
    }

    private void send(DeckRequest r) {
        try {
            sender.send(compose(r));
            requests.markNotified(r.id(), clock.instant());
        } catch (Exception e) {
            log.warn("Deck request email for {} failed: {}", r.id(), e.getMessage());          // messages never contain keys or addresses' secrets
        }
    }

    Email compose(DeckRequest r) {
        String link = frontendBaseUrl + "/#/admin/requests/" + r.id();
        String who = r.requesterUsername() == null ? "(unknown)" : r.requesterUsername();
        boolean promotion = r.type() == com.cards.common.DeckRequestType.PROMOTE;
        String intro = promotion ? "The owner of a private deck asks to make it global." : "A new deck was requested.";
        String subject = (promotion ? "Make deck global: " : "New deck request: ") + r.category();
        String text = intro + "\n\n" + (promotion ? "Deck: " : "Category: ") + r.category() + "\nRequested by: " + who
                + (promotion ? "\nCards: " : "\nSuggested names: ") + r.names().size() + "\n\nReview it: " + link + "\n";
        String html = "<p>" + intro + "</p><ul>"
                + "<li><b>" + (promotion ? "Deck:" : "Category:") + "</b> " + HtmlUtils.htmlEscape(r.category()) + "</li>"
                + "<li><b>Requested by:</b> " + HtmlUtils.htmlEscape(who) + "</li>"
                + "<li><b>" + (promotion ? "Cards:" : "Suggested names:") + "</b> " + r.names().size() + "</li></ul>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Review the request</a></p>";
        return new Email(recipients, subject, html, text);
    }
}
