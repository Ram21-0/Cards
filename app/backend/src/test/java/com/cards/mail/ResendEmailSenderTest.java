package com.cards.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real HTTP code path against a local fake of the provider: no real email can be sent from a test. */
class ResendEmailSenderTest {

    private static final String KEY = "re_test_key_that_must_never_be_logged";
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> auth = new AtomicReference<>(), body = new AtomicReference<>(), contentType = new AtomicReference<>();
    private volatile int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = ("{\"id\":\"x\",\"echo\":\"" + KEY + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ResendEmailSender sender() {
        return new ResendEmailSender(HttpClient.newHttpClient(), json, "http://127.0.0.1:" + server.getAddress().getPort() + "/emails", KEY, "Cards <cards@example.com>");
    }

    private static Email email() {
        return new Email(List.of("boss@example.com"), "Subject", "<p>Hi</p>", "Hi");
    }

    @Test
    void postsTheEmailWithABearerKeyAndJson() throws Exception {
        sender().send(email());

        assertEquals("Bearer " + KEY, auth.get());
        assertEquals("application/json", contentType.get());
        JsonNode sent = json.readTree(body.get());
        assertEquals("Cards <cards@example.com>", sent.get("from").asText());
        assertEquals("boss@example.com", sent.get("to").get(0).asText());
        assertEquals("Subject", sent.get("subject").asText());
        assertEquals("<p>Hi</p>", sent.get("html").asText());
        assertEquals("Hi", sent.get("text").asText());
        assertFalse(body.get().contains(KEY), "the key is only in the header");
    }

    @Test
    void aProviderErrorBecomesAnExceptionWithoutTheKeyOrTheResponseBody() {
        status = 422;
        EmailSender.EmailException e = assertThrows(EmailSender.EmailException.class, () -> sender().send(email()));
        assertEquals("The email provider answered HTTP 422", e.getMessage());
        assertFalse(e.getMessage().contains(KEY));
    }

    @Test
    void anUnreachableProviderBecomesAnExceptionWithoutTheKey() {
        ResendEmailSender unreachable = new ResendEmailSender(HttpClient.newHttpClient(), json, "http://127.0.0.1:1/emails", KEY, "a@b.c");
        EmailSender.EmailException e = assertThrows(EmailSender.EmailException.class, () -> unreachable.send(email()));
        assertTrue(e.getMessage().startsWith("The email provider could not be reached"));
        assertFalse(e.getMessage().contains(KEY));
    }

    @Test
    void withoutBothSettingsTheConfigurationLogsInsteadOfSending() {
        EmailConfig config = new EmailConfig();
        assertEquals(LogEmailSender.class, config.emailSender(json, "", "").getClass());
        assertEquals(LogEmailSender.class, config.emailSender(json, KEY, "").getClass());
        assertEquals(LogEmailSender.class, config.emailSender(json, "", "a@b.c").getClass());
        assertTrue(config.emailSender(json, KEY, "a@b.c") instanceof ResendEmailSender);
    }
}
