package com.cards.service.controller;

import com.cards.mail.Email;
import com.cards.mail.EmailSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deck requests end to end: real HTTP, real Postgres (a scratch database this test creates and drops; skipped when no Postgres is
 * reachable), the real security rules, and a fake mail sender so nothing is ever really sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true", "app.admin.notify-to=boss@example.com", "app.admin.emails=listed@example.com"})
@EnabledIf("com.cards.service.controller.DeckRequestIntegrationTest#postgresAvailable")
@Import(DeckRequestIntegrationTest.TestBeans.class)
class DeckRequestIntegrationTest {

    private static final String HOST = env("TEST_PG_HOST", "localhost");
    private static final String PORT = env("TEST_PG_PORT", "5433");
    private static final String USER = env("TEST_PG_USER", System.getProperty("user.name"));
    private static final String PASSWORD = env("TEST_PG_PASSWORD", "x");
    private static final String DB = "cards_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static Connection admin() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://" + HOST + ":" + PORT + "/postgres", USER, PASSWORD);
    }

    static boolean postgresAvailable() {
        try (Connection ignored = admin()) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void scratchDatabase(DynamicPropertyRegistry registry) throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("create database " + DB);
        }
        registry.add("DATABASE_URL", () -> "postgresql://" + USER + ":" + PASSWORD + "@" + HOST + ":" + PORT + "/" + DB);
    }

    @AfterAll
    static void dropScratchDatabase() throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("drop database if exists " + DB + " with (force)");
        }
    }

    static final List<Email> SENT = new CopyOnWriteArrayList<>();
    static final AtomicBoolean MAIL_FAILS = new AtomicBoolean();

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        EmailSender fakeEmailSender() {
            return email -> {
                if (MAIL_FAILS.get()) throw new EmailSender.EmailException("The email provider answered HTTP 500");
                SENT.add(email);
            };
        }

        @Bean(name = "mailExecutor")
        Executor directMailExecutor() {
            return Runnable::run;
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clean() {
        assertEquals(DB, jdbc.queryForObject("select current_database()", String.class), "refusing to run outside the scratch database");
        SENT.clear();
        MAIL_FAILS.set(false);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static HttpEntity<String> entity(String body, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Requested-With", "cards");
        if (token != null) h.add(HttpHeaders.COOKIE, "session=" + token);
        return new HttpEntity<>(body, h);
    }

    private String login(String username, boolean asAdmin) throws Exception {
        ResponseEntity<String> r = rest.postForEntity("/api/dev/login" + (asAdmin ? "?admin=true" : ""), entity("{\"username\":\"" + username + "\"}", null), String.class);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        assertEquals(asAdmin, json.readTree(r.getBody()).get("isAdmin").asBoolean());
        String cookie = r.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return cookie.substring("session=".length(), cookie.indexOf(';'));
    }

    private JsonNode call(HttpMethod method, String path, String token, String body, int expected) throws Exception {
        ResponseEntity<String> r = rest.exchange(path, method, entity(body, token), String.class);
        assertEquals(expected, r.getStatusCode().value(), method + " " + path + " -> " + r.getBody());
        return r.getBody() == null || r.getBody().isBlank() ? null : json.readTree(r.getBody());
    }

    private static String body(String category, int names) {
        StringBuilder list = new StringBuilder();
        for (int i = 1; i <= names; i++) list.append(i > 1 ? "," : "").append("\"Player ").append(i).append('"');
        return "{\"category\":\"" + category + "\",\"description\":\"Test\",\"names\":[" + list + "]}";
    }

    private JsonNode request(String token, String category) throws Exception {
        return call(HttpMethod.POST, "/api/deck-requests", token, body(category, 5), 201);
    }

    // ------------------------------------------------------------------------------------------ admin access

    @Test
    void adminAccessIsSeparateFromSigningIn() throws Exception {
        String user = login("dr_user1", false), boss = login("dr_boss1", true);
        assertFalse(call(HttpMethod.GET, "/api/me", user, null, 200).get("isAdmin").asBoolean());
        assertTrue(call(HttpMethod.GET, "/api/me", boss, null, 200).get("isAdmin").asBoolean());

        call(HttpMethod.GET, "/api/admin/deck-requests", null, null, 401);
        call(HttpMethod.GET, "/api/admin/deck-requests", user, null, 403);
        call(HttpMethod.GET, "/api/admin/deck-requests", boss, null, 200);
        call(HttpMethod.POST, "/api/admin/deck-requests/" + UUID.randomUUID() + "/approve", user, "{\"visibility\":\"PUBLIC\"}", 403);
        call(HttpMethod.POST, "/api/admin/deck-requests/" + UUID.randomUUID() + "/approve", boss, "{\"visibility\":\"PUBLIC\"}", 404);

        // the cookie rule: a state change without the header is refused even for an admin
        HttpHeaders noHeader = new HttpHeaders();
        noHeader.setContentType(MediaType.APPLICATION_JSON);
        noHeader.add(HttpHeaders.COOKIE, "session=" + boss);
        ResponseEntity<String> r = rest.exchange("/api/admin/deck-requests/" + UUID.randomUUID() + "/reject", HttpMethod.POST, new HttpEntity<>("{\"note\":\"x\"}", noHeader), String.class);
        assertEquals(403, r.getStatusCode().value());
    }

    // ------------------------------------------------------------------------------------------ user flow

    @Test
    void aRequestIsStoredListedAndTheAdminIsMailedExactlyOnce() throws Exception {
        String ann = login("dr_ann1", false);
        call(HttpMethod.POST, "/api/deck-requests", null, body("Cricket legends", 5), 401);

        JsonNode created = request(ann, "Cricket legends");
        String id = created.get("id").asText();
        assertEquals("PENDING", created.get("status").asText());
        assertFalse(created.get("live").asBoolean());
        assertEquals(5, created.get("names").size());

        assertEquals(1, SENT.size(), "one mail for one request");
        Email mail = SENT.get(0);
        assertEquals(List.of("boss@example.com"), mail.to());
        assertTrue(mail.text().contains("Cricket legends") && mail.text().contains("dr_ann1") && mail.text().contains("Suggested names: 5"));
        assertTrue(mail.text().contains("/#/admin/requests/" + id), mail.text());
        assertNotNull(jdbc.queryForObject("select notified_at from deck_requests where id = ?::uuid", java.sql.Timestamp.class, id), "notified_at is recorded");

        JsonNode mine = call(HttpMethod.GET, "/api/deck-requests", ann, null, 200);
        assertEquals(1, mine.size());
        assertEquals(id, mine.get(0).get("id").asText());
        call(HttpMethod.GET, "/api/deck-requests/" + id, ann, null, 200);
        call(HttpMethod.GET, "/api/deck-requests/" + id, login("dr_eve1", false), null, 404);          // somebody else's
    }

    @Test
    void aFailingMailNeverBreaksTheRequest() throws Exception {
        MAIL_FAILS.set(true);
        String ann = login("dr_ann2", false);
        JsonNode created = request(ann, "Football icons");
        assertEquals("PENDING", created.get("status").asText());
        assertTrue(SENT.isEmpty());
        assertNull(jdbc.queryForObject("select notified_at from deck_requests where id = ?::uuid", java.sql.Timestamp.class, created.get("id").asText()), "not marked as notified");
        assertEquals(1, call(HttpMethod.GET, "/api/deck-requests", ann, null, 200).size());
    }

    @Test
    void validationAndTheLimitsAreEnforced() throws Exception {
        String ann = login("dr_ann3", false), boss = login("dr_boss3", true);
        call(HttpMethod.POST, "/api/deck-requests", ann, body("ab", 5), 400);
        call(HttpMethod.POST, "/api/deck-requests", ann, body("Tennis stars", 2), 400);
        call(HttpMethod.POST, "/api/deck-requests", ann, body("Tennis stars", 61), 400);
        call(HttpMethod.POST, "/api/deck-requests", ann, "{\"category\":\"Tennis stars\"}", 400);
        assertEquals(0, jdbc.queryForObject("select count(*) from deck_requests dr join players p on p.id = dr.requester_id where p.username = 'dr_ann3'", Integer.class).intValue());

        String[] ids = new String[3];
        for (int i = 0; i < 3; i++) ids[i] = request(ann, "Category " + i).get("id").asText();
        call(HttpMethod.POST, "/api/deck-requests", ann, body("One too many", 5), 409);           // 3 pending

        call(HttpMethod.POST, "/api/admin/deck-requests/" + ids[0] + "/reject", boss, "{\"note\":\"no\"}", 200);
        call(HttpMethod.POST, "/api/admin/deck-requests/" + ids[1] + "/reject", boss, "{\"note\":\"no\"}", 200);
        request(ann, "Category 3");
        request(ann, "Category 4");                                                                 // 5 in the last 24 h
        call(HttpMethod.POST, "/api/admin/deck-requests/" + ids[2] + "/reject", boss, "{\"note\":\"no\"}", 200);
        call(HttpMethod.POST, "/api/deck-requests", ann, body("Category 5", 5), 429);              // the daily limit, although only 2 are pending
    }

    // ------------------------------------------------------------------------------------------ admin flow

    @Test
    void approvalCreatesANotLiveDeckThatIsNeverListedOrPlayable() throws Exception {
        String ann = login("dr_ann4", false), boss = login("dr_boss4", true);
        String id = request(ann, "Rugby heroes").get("id").asText();

        JsonNode list = call(HttpMethod.GET, "/api/admin/deck-requests?status=PENDING", boss, null, 200);
        assertTrue(list.toString().contains(id));
        assertEquals("dr_ann4", call(HttpMethod.GET, "/api/admin/deck-requests/" + id, boss, null, 200).get("requester").get("username").asText());

        call(HttpMethod.PATCH, "/api/admin/deck-requests/" + id, boss, "{\"names\":[\" Jonah Lomu \",\"jonah lomu\",\"Richie McCaw\",\"Dan Carter\"]}", 200);
        JsonNode edited = call(HttpMethod.GET, "/api/admin/deck-requests/" + id, boss, null, 200);
        assertEquals(3, edited.get("names").size());

        call(HttpMethod.POST, "/api/admin/deck-requests/" + id + "/approve", boss, "{\"visibility\":\"UNLISTED\"}", 400);
        JsonNode approved = call(HttpMethod.POST, "/api/admin/deck-requests/" + id + "/approve", boss, "{\"visibility\":\"PUBLIC\",\"note\":\"Great idea\"}", 200);
        assertEquals("APPROVED_GLOBAL", approved.get("status").asText());
        assertEquals("Great idea", approved.get("adminNote").asText());
        assertFalse(approved.get("live").asBoolean());
        String deckId = approved.get("deckId").asText();
        assertNotNull(approved.get("decidedBy").asText());
        assertEquals(jdbc.queryForObject("select id::text from players where username = 'dr_boss4'", String.class), approved.get("decidedBy").asText());

        // the deck record: owned by the requester, named after the category, not live, no stats
        assertEquals("Rugby heroes", jdbc.queryForObject("select name from decks where id = ?::uuid", String.class, deckId));
        assertEquals(jdbc.queryForObject("select id from players where username = 'dr_ann4'", UUID.class),
                jdbc.queryForObject("select owner_id from decks where id = ?::uuid", UUID.class, deckId));
        assertFalse(jdbc.queryForObject("select live from decks where id = ?::uuid", Boolean.class, deckId));
        assertEquals(0, jdbc.queryForObject("select count(*) from deck_stats where deck_id = ?::uuid", Integer.class, deckId).intValue());

        // never listed, never readable, never playable
        assertFalse(call(HttpMethod.GET, "/api/decks", null, null, 200).toString().contains(deckId));
        call(HttpMethod.GET, "/api/decks/" + deckId, null, null, 404);
        call(HttpMethod.GET, "/api/decks/" + deckId + "/cards", null, null, 404);
        call(HttpMethod.POST, "/api/games", ann, "{\"deckId\":\"" + deckId + "\",\"maxPlayers\":2}", 404);
        // the built-in deck is unaffected
        assertTrue(call(HttpMethod.GET, "/api/decks", null, null, 200).toString().contains("Wrestling"));

        // decided once
        call(HttpMethod.POST, "/api/admin/deck-requests/" + id + "/approve", boss, "{\"visibility\":\"PRIVATE\"}", 409);
        call(HttpMethod.POST, "/api/admin/deck-requests/" + id + "/reject", boss, "{\"note\":\"late\"}", 409);
        call(HttpMethod.PATCH, "/api/admin/deck-requests/" + id, boss, "{\"names\":[\"A\",\"B\",\"C\"]}", 409);
        // the requester sees the decision, but not live
        JsonNode mine = call(HttpMethod.GET, "/api/deck-requests/" + id, ann, null, 200);
        assertEquals("APPROVED_GLOBAL", mine.get("status").asText());
        assertFalse(mine.get("live").asBoolean());
        assertTrue(mine.get("deckId").isNull(), "the deck id is shown to the requester once the deck is live");
    }

    @Test
    void aPrivateApprovalAndARejectionWithANote() throws Exception {
        String ann = login("dr_ann5", false), boss = login("dr_boss5", true);
        String first = request(ann, "Chess masters").get("id").asText(), second = request(ann, "Poker pros").get("id").asText();

        assertEquals("APPROVED_PRIVATE", call(HttpMethod.POST, "/api/admin/deck-requests/" + first + "/approve", boss, "{\"visibility\":\"PRIVATE\"}", 200).get("status").asText());
        call(HttpMethod.POST, "/api/admin/deck-requests/" + second + "/reject", boss, "{}", 400);                // a note is required
        JsonNode rejected = call(HttpMethod.POST, "/api/admin/deck-requests/" + second + "/reject", boss, "{\"note\":\"Too broad\"}", 200);
        assertEquals("REJECTED", rejected.get("status").asText());
        assertNull(rejected.get("deckId") == null || rejected.get("deckId").isNull() ? null : "has a deck");
        assertEquals("Too broad", call(HttpMethod.GET, "/api/deck-requests/" + second, ann, null, 200).get("adminNote").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from decks where name = 'Poker pros'", Integer.class).intValue(), "a rejection creates no deck");

        // pending first, then the rest
        String third = request(ann, "Golf greats").get("id").asText();
        JsonNode all = call(HttpMethod.GET, "/api/admin/deck-requests", boss, null, 200);
        assertEquals(third, all.get(0).get("id").asText(), "pending requests come first");
        call(HttpMethod.GET, "/api/admin/deck-requests?status=REJECTED", boss, null, 200);
        call(HttpMethod.GET, "/api/admin/deck-requests?status=BOGUS", boss, null, 400);
    }

    @Test
    void anOversizedBodyIsRefused() throws Exception {
        String ann = login("dr_ann6", false);
        String huge = "{\"category\":\"Big\",\"description\":\"" + "x".repeat(70_000) + "\",\"names\":[\"A\",\"B\",\"C\"]}";
        call(HttpMethod.POST, "/api/deck-requests", ann, huge, 413);
    }
}
