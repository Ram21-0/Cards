package com.cards.service.service;

import com.cards.TestClock;
import com.cards.common.DeckRequest;
import com.cards.common.DeckRequestStatus;
import com.cards.mail.Email;
import com.cards.mail.EmailSender;
import com.cards.service.db.DeckRequestRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DeckRequestNotifierTest {

    private final List<Email> sent = new ArrayList<>();
    private final DeckRequestRepository requests = mock(DeckRequestRepository.class);
    private final TestClock clock = TestClock.atNow();
    private final Executor sameThread = Runnable::run;

    private DeckRequest request(String category, String username) {
        return DeckRequest.builder().id(UUID.randomUUID()).requesterId(UUID.randomUUID()).requesterUsername(username).category(category)
                .names(List.of("A", "B", "C")).status(DeckRequestStatus.PENDING).createdAt(clock.instant()).build();
    }

    private DeckRequestNotifier notifier(EmailSender sender, String to) {
        return new DeckRequestNotifier(sender, requests, sameThread, clock, to, "https://cards.example.com/");
    }

    @Test
    void theAdminGetsOneMailWithCategoryRequesterCountAndLinkAndNotifiedAtIsRecorded() {
        DeckRequest r = request("Cricket legends", "ann");
        notifier(sent::add, "boss@example.com, second@example.com").created(r);

        assertEquals(1, sent.size());
        Email mail = sent.get(0);
        assertEquals(List.of("boss@example.com", "second@example.com"), mail.to());
        assertEquals("New deck request: Cricket legends", mail.subject());
        assertTrue(mail.text().contains("Category: Cricket legends") && mail.text().contains("Requested by: ann") && mail.text().contains("Suggested names: 3"));
        assertTrue(mail.text().contains("https://cards.example.com/#/admin/requests/" + r.id()));
        assertTrue(mail.html().contains("href=\"https://cards.example.com/#/admin/requests/" + r.id() + "\""));
        verify(requests).markNotified(r.id(), clock.instant());
    }

    @Test
    void everythingUserSuppliedIsHtmlEscaped() {
        notifier(sent::add, "boss@example.com").created(request("<script>alert(1)</script> & \"q\"", "<b>eve</b>"));
        String html = sent.get(0).html();
        assertFalse(html.contains("<script>") || html.contains("<b>eve</b>"), html);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;q&quot;") && html.contains("&lt;b&gt;eve&lt;/b&gt;"), html);
    }

    @Test
    void aFailingMailNeverThrowsAndIsNotMarkedAsNotified() {
        EmailSender failing = email -> { throw new EmailSender.EmailException("The email provider answered HTTP 500"); };
        DeckRequest r = request("Cricket", "ann");
        notifier(failing, "boss@example.com").created(r);
        verify(requests, never()).markNotified(any(), any());
        EmailSender crashing = email -> { throw new IllegalStateException("boom"); };
        notifier(crashing, "boss@example.com").created(r);
        verify(requests, never()).markNotified(any(), any());
    }

    @Test
    void noRecipientMeansNoMail() {
        notifier(sent::add, "  ").created(request("Cricket", "ann"));
        assertTrue(sent.isEmpty());
        verify(requests, never()).markNotified(any(), any());
    }

    @Test
    void aRejectedExecutorDoesNotBreakTheRequest() {
        Executor full = command -> { throw new java.util.concurrent.RejectedExecutionException("full"); };
        new DeckRequestNotifier(sent::add, requests, full, clock, "boss@example.com", "https://x").created(request("Cricket", "ann"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void theMailIsSentOnTheExecutorNotOnTheCallingThread() {
        List<Runnable> queued = new ArrayList<>();
        new DeckRequestNotifier(sent::add, requests, queued::add, clock, "boss@example.com", "https://x").created(request("Cricket", "ann"));
        assertTrue(sent.isEmpty(), "nothing is sent until the executor runs it");
        queued.forEach(Runnable::run);
        assertEquals(1, sent.size());
    }
}
