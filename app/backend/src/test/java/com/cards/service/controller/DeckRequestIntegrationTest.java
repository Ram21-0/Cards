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

    // ------------------------------------------------------------------------------------------ decks of your own (Flow 2)

    private static final String MAPPING = "{\"stats\":[{\"column\":\"rank\",\"label\":\"Rank\",\"higherWins\":false},"
            + "{\"column\":\"power\",\"label\":\"Power\",\"unit\":\"pts\",\"higherWins\":true,\"displayFormat\":\"WITH_UNIT\"}]}";

    private static String deckCsv(int rows, boolean withImages) {
        StringBuilder sb = new StringBuilder("name,image,brand,rank,power\n");
        for (int i = 1; i <= rows; i++) sb.append("Hero ").append(i).append(',').append(withImages ? "hero" + i + ".jpg" : "").append(",raw,").append(i).append(',').append(10 + i).append('\n');
        return sb.toString();
    }

    private static byte[] imagesZip(int count) {
        Object[] entries = new Object[count * 2];
        for (int i = 1; i <= count; i++) {
            entries[(i - 1) * 2] = "pics/hero" + i + ".jpg";
            entries[(i - 1) * 2 + 1] = com.cards.deckimport.TestImagesAccess.jpeg(100 + i, 120);
        }
        return com.cards.deckimport.TestImagesAccess.zip(entries);
    }

    private ResponseEntity<String> upload(String method, String path, String token, String csv, String mapping, String name, byte[] zip) {
        org.springframework.util.LinkedMultiValueMap<String, Object> parts = new org.springframework.util.LinkedMultiValueMap<>();
        if (csv != null) parts.add("csv", namedFile("cards.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (mapping != null) parts.add("mapping", mapping);
        if (name != null) parts.add("name", name);
        if (zip != null) parts.add("images", namedFile("images.zip", zip));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.add("X-Requested-With", "cards");
        if (token != null) h.add(HttpHeaders.COOKIE, "session=" + token);
        return rest.exchange(path, HttpMethod.valueOf(method), new HttpEntity<>(parts, h), String.class);
    }

    private static org.springframework.core.io.ByteArrayResource namedFile(String filename, byte[] bytes) {
        return new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    /** Uploads 8 heroes with images and returns the new deck's id. */
    private String uploadDeck(String token, String name) throws Exception {
        ResponseEntity<String> r = upload("POST", "/api/decks/import", token, deckCsv(8, true), MAPPING, name, imagesZip(8));
        assertEquals(201, r.getStatusCode().value(), r.getBody());
        return json.readTree(r.getBody()).get("id").asText();
    }

    @Test
    void aUserUploadsACsvWithImagesAndGetsAPrivateDeckNobodyElseCanRead() throws Exception {
        String ann = login("up_ann1", false), eve = login("up_eve1", false), boss = login("up_boss1", true);

        JsonNode template = call(HttpMethod.GET, "/api/decks/import/template", ann, null, 200);
        assertTrue(template.get("csv").asText().startsWith("name,image,brand,rank"));
        assertEquals(6, template.get("mapping").get("stats").size());

        // preview: every problem at once, nothing saved
        ResponseEntity<String> bad = upload("POST", "/api/decks/import?dryRun=true", ann, deckCsv(8, true), MAPPING, "Heroes", imagesZip(6));
        assertEquals(200, bad.getStatusCode().value());
        JsonNode badBody = json.readTree(bad.getBody());
        assertFalse(badBody.get("valid").asBoolean());
        assertEquals(2, badBody.get("errorCount").asInt(), "hero7 and hero8 have no image file: " + bad.getBody());
        ResponseEntity<String> good = upload("POST", "/api/decks/import?dryRun=true", ann, deckCsv(8, true), MAPPING, "Heroes", imagesZip(8));
        JsonNode goodBody = json.readTree(good.getBody());
        assertTrue(goodBody.get("valid").asBoolean(), good.getBody());
        assertEquals(8, goodBody.get("cards").size());
        assertEquals("INT", goodBody.get("stats").get(0).get("valueType").asText());
        assertEquals(0, call(HttpMethod.GET, "/api/decks/mine", ann, null, 200).size(), "a preview saves nothing");
        assertEquals(0, jdbc.queryForObject("select count(*) from images", Integer.class).intValue() - imagesBefore(), "and stores no images");

        // commit
        String deckId = uploadDeck(ann, "Heroes of mine");
        JsonNode mine = call(HttpMethod.GET, "/api/decks/mine", ann, null, 200);
        assertEquals(1, mine.size());
        assertEquals("PRIVATE", mine.get(0).get("visibility").asText());
        assertTrue(mine.get(0).get("live").asBoolean());
        assertEquals(8, mine.get(0).get("cardCount").asInt());
        assertTrue(mine.get(0).get("promotion").isNull());

        // the owner reads it, with images; they are served with long cache headers and were re-encoded
        JsonNode cards = call(HttpMethod.GET, "/api/decks/" + deckId + "/cards", ann, null, 200);
        assertEquals(8, cards.size());
        String img = cards.get(0).get("img").asText();
        assertTrue(img.startsWith("/api/images/"), img);
        assertEquals("Power", call(HttpMethod.GET, "/api/decks/" + deckId, ann, null, 200).get("stats").get(1).get("label").asText());
        assertEquals("11pts", cards.get(0).get("stats").get("power").get("displayValue").asText());
        ResponseEntity<byte[]> served = rest.getForEntity(img, byte[].class);
        assertEquals(200, served.getStatusCode().value());
        assertEquals("image/jpeg", served.getHeaders().getContentType().toString());
        assertTrue(served.getHeaders().getCacheControl().contains("max-age=31536000") && served.getHeaders().getCacheControl().contains("immutable"), served.getHeaders().getCacheControl());
        assertEquals("nosniff", served.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals(304, rest.exchange(img, HttpMethod.GET, new HttpEntity<>(null, etagHeader(served)), byte[].class).getStatusCode().value());
        assertEquals(404, rest.getForEntity("/api/images/" + UUID.randomUUID(), String.class).getStatusCode().value());

        // nobody else reads it; an admin may; the public list does not show it, the owner's list does
        call(HttpMethod.GET, "/api/decks/" + deckId, eve, null, 404);
        call(HttpMethod.GET, "/api/decks/" + deckId + "/cards", eve, null, 404);
        call(HttpMethod.GET, "/api/decks/" + deckId, null, null, 404);
        call(HttpMethod.GET, "/api/decks/" + deckId, boss, null, 200);
        assertFalse(call(HttpMethod.GET, "/api/decks", null, null, 200).toString().contains(deckId));
        assertFalse(call(HttpMethod.GET, "/api/decks", eve, null, 200).toString().contains(deckId));
        assertTrue(call(HttpMethod.GET, "/api/decks", ann, null, 200).toString().contains(deckId));
        assertEquals(0, call(HttpMethod.GET, "/api/decks/mine", eve, null, 200).size());
    }

    private int imagesBefore() {
        return jdbc.queryForObject("select count(*) from images", Integer.class);
    }

    private static HttpHeaders etagHeader(ResponseEntity<byte[]> served) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.IF_NONE_MATCH, served.getHeaders().getETag());
        return h;
    }

    @Test
    void hostileUploadsAreRefusedWithoutSavingAnything() throws Exception {
        String ann = login("up_ann2", false);
        int imagesBefore = imagesBefore();
        // signed out, missing header, missing parts
        assertEquals(401, upload("POST", "/api/decks/import", null, deckCsv(8, false), MAPPING, "Deck", null).getStatusCode().value());
        assertEquals(400, upload("POST", "/api/decks/import", ann, null, MAPPING, "Deck", null).getStatusCode().value());
        assertEquals(400, upload("POST", "/api/decks/import", ann, deckCsv(8, false), null, "Deck", null).getStatusCode().value());
        // a zip bomb, an image that is really HTML, a CSV with a bad mapping, an oversize image
        ResponseEntity<String> bomb = upload("POST", "/api/decks/import", ann, deckCsv(8, true), MAPPING, "Deck", com.cards.deckimport.TestImagesAccess.zip("hero1.jpg", new byte[1]).length > 0 ? bombZip() : null);
        assertEquals(400, bomb.getStatusCode().value());
        assertTrue(bomb.getBody().contains("when unpacked"), bomb.getBody());
        ResponseEntity<String> html = upload("POST", "/api/decks/import", ann, deckCsv(8, true), MAPPING, "Deck", com.cards.deckimport.TestImagesAccess.zip("hero1.jpg", "<html><script>alert(1)</script></html>".getBytes()));
        assertEquals(400, html.getStatusCode().value());
        assertTrue(html.getBody().contains("only PNG, JPEG and GIF images are accepted"), html.getBody());
        assertEquals(400, upload("POST", "/api/decks/import", ann, deckCsv(8, false), "{\"stats\":[]}", "Deck", null).getStatusCode().value());
        assertEquals(400, upload("POST", "/api/decks/import", ann, deckCsv(7, false), MAPPING, "Deck", null).getStatusCode().value(), "fewer than 8 cards");
        assertEquals(400, upload("POST", "/api/decks/import", ann, deckCsv(8, false), MAPPING, "x", null).getStatusCode().value(), "name too short");
        assertEquals(0, call(HttpMethod.GET, "/api/decks/mine", ann, null, 200).size());
        assertEquals(imagesBefore, imagesBefore(), "nothing was stored");
    }

    private static byte[] bombZip() {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("bomb.bin"));
                byte[] block = new byte[1024 * 1024];
                for (int i = 0; i < 40; i++) zip.write(block);
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theNumberOfDecksPerUserIsLimitedAndDeletingFreesASlot() throws Exception {
        String ann = login("up_ann3", false);
        String first = null;
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<String> r = upload("POST", "/api/decks/import", ann, deckCsv(8, false), MAPPING, "Deck number " + i, null);
            assertEquals(201, r.getStatusCode().value(), r.getBody());
            if (first == null) first = json.readTree(r.getBody()).get("id").asText();
        }
        assertEquals(409, upload("POST", "/api/decks/import", ann, deckCsv(8, false), MAPPING, "Deck number 6", null).getStatusCode().value());
        call(HttpMethod.DELETE, "/api/decks/" + first, ann, null, 204);
        assertEquals(4, call(HttpMethod.GET, "/api/decks/mine", ann, null, 200).size());
        assertEquals(201, upload("POST", "/api/decks/import", ann, deckCsv(8, false), MAPPING, "Deck number 6", null).getStatusCode().value());
    }

    @Test
    void deletingADeckRemovesItsImagesButNotOnesSharedWithACopy() throws Exception {
        String ann = login("up_ann4", false), eve = login("up_eve4", false);
        String deckId = uploadDeck(ann, "Deck to delete");
        int imagesWithDeck = imagesBefore();
        call(HttpMethod.POST, "/api/decks/" + deckId + "/copy", eve, "{}", 404);                // not yours
        JsonNode copy = call(HttpMethod.POST, "/api/decks/" + deckId + "/copy", ann, "{\"name\":\"Copy of heroes\"}", 201);
        assertEquals("PRIVATE", copy.get("visibility").asText());
        assertEquals(8, call(HttpMethod.GET, "/api/decks/" + copy.get("id").asText() + "/cards", ann, null, 200).size());
        assertEquals(imagesWithDeck, imagesBefore(), "a copy shares the stored images");

        call(HttpMethod.DELETE, "/api/decks/" + deckId, eve, null, 404);
        call(HttpMethod.DELETE, "/api/decks/" + deckId, ann, null, 204);
        assertEquals(imagesWithDeck, imagesBefore(), "the copy still uses the images");
        call(HttpMethod.DELETE, "/api/decks/" + copy.get("id").asText(), ann, null, 204);
        assertEquals(imagesWithDeck - 8, imagesBefore(), "now nothing uses them");
    }

    @Test
    void renamingReplacingAndTheEditRules() throws Exception {
        String ann = login("up_ann5", false), eve = login("up_eve5", false);
        String deckId = uploadDeck(ann, "First name");
        assertEquals("Better name", call(HttpMethod.PATCH, "/api/decks/" + deckId, ann, "{\"name\":\"Better name\"}", 200).get("name").asText());
        call(HttpMethod.PATCH, "/api/decks/" + deckId, eve, "{\"name\":\"Hijacked\"}", 404);
        call(HttpMethod.PATCH, "/api/decks/" + deckId, ann, "{\"name\":\"x\"}", 400);

        // a replacement upload swaps cards, stats and images
        ResponseEntity<String> replaced = upload("PUT", "/api/decks/" + deckId + "/import", ann, deckCsv(9, false), MAPPING, null, null);
        assertEquals(200, replaced.getStatusCode().value(), replaced.getBody());
        assertEquals(9, call(HttpMethod.GET, "/api/decks/" + deckId + "/cards", ann, null, 200).size());
        assertEquals("Better name", json.readTree(replaced.getBody()).get("name").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from cards where deck_id = ?::uuid and img_url is not null", Integer.class, deckId).intValue());
        assertEquals(404, upload("PUT", "/api/decks/" + deckId + "/import", eve, deckCsv(8, false), MAPPING, null, null).getStatusCode().value());
    }

    // ------------------------------------------------------------------------------------------ promotion

    @Test
    void theOwnerAsksToMakeAPrivateDeckGlobalAndTheAdminDecides() throws Exception {
        String ann = login("up_ann6", false), eve = login("up_eve6", false), boss = login("up_boss6", true);
        String deckId = uploadDeck(ann, "Worth sharing");

        call(HttpMethod.POST, "/api/decks/" + deckId + "/request-global", eve, null, 404);
        JsonNode requested = call(HttpMethod.POST, "/api/decks/" + deckId + "/request-global", ann, null, 201);
        String requestId = requested.get("id").asText();
        assertEquals("PROMOTE", requested.get("type").asText());
        assertEquals("PENDING", requested.get("status").asText());
        assertEquals(deckId, requested.get("deckId").asText());
        call(HttpMethod.POST, "/api/decks/" + deckId + "/request-global", ann, null, 409);        // one open request per deck

        Email mail = SENT.get(SENT.size() - 1);
        assertEquals("Make deck global: Worth sharing", mail.subject());
        assertTrue(mail.text().contains("Cards: 8") && mail.text().contains("up_ann6") && mail.text().contains("/#/admin/requests/" + requestId), mail.text());

        // while it is open: no edits, and the owner sees the state in their list
        assertEquals(409, upload("PUT", "/api/decks/" + deckId + "/import", ann, deckCsv(8, false), MAPPING, null, null).getStatusCode().value());
        call(HttpMethod.PATCH, "/api/decks/" + deckId, ann, "{\"name\":\"Changed meanwhile\"}", 409);
        assertEquals("PENDING", call(HttpMethod.GET, "/api/decks/mine", ann, null, 200).get(0).get("promotion").get("status").asText());

        // the admin queue lists it, with the deck and its cards for review
        JsonNode queue = call(HttpMethod.GET, "/api/admin/deck-requests?status=PENDING", boss, null, 200);
        assertTrue(queue.toString().contains(requestId) && queue.toString().contains("\"type\":\"PROMOTE\""));
        assertEquals(8, call(HttpMethod.GET, "/api/admin/deck-requests/" + requestId + "/cards", boss, null, 200).size());
        call(HttpMethod.PATCH, "/api/admin/deck-requests/" + requestId, boss, "{\"names\":[\"A\",\"B\",\"C\"]}", 409);
        call(HttpMethod.POST, "/api/admin/deck-requests/" + requestId + "/publish", boss, null, 409);
        call(HttpMethod.POST, "/api/admin/deck-requests/" + requestId + "/approve", boss, "{\"visibility\":\"PRIVATE\"}", 400);

        // reject: it stays private, with the note, and can be edited again
        JsonNode rejected = call(HttpMethod.POST, "/api/admin/deck-requests/" + requestId + "/reject", boss, "{\"note\":\"Needs better pictures\"}", 200);
        assertEquals("REJECTED", rejected.get("status").asText());
        JsonNode afterReject = call(HttpMethod.GET, "/api/decks/mine", ann, null, 200).get(0);
        assertEquals("REJECTED", afterReject.get("promotion").get("status").asText());
        assertEquals("Needs better pictures", afterReject.get("promotion").get("adminNote").asText());
        assertEquals("PRIVATE", afterReject.get("visibility").asText());
        call(HttpMethod.PATCH, "/api/decks/" + deckId, ann, "{\"description\":\"Better now\"}", 200);

        // ask again; this time the admin approves: the deck is PUBLIC at once, the owner keeps it
        String second = call(HttpMethod.POST, "/api/decks/" + deckId + "/request-global", ann, null, 201).get("id").asText();
        JsonNode approved = call(HttpMethod.POST, "/api/admin/deck-requests/" + second + "/approve", boss, "{\"note\":\"Nice\"}", 200);
        assertEquals("APPROVED_GLOBAL", approved.get("status").asText());
        assertTrue(approved.get("live").asBoolean());
        assertEquals("PUBLIC", call(HttpMethod.GET, "/api/decks/" + deckId, eve, null, 200).get("visibility").asText());
        assertTrue(call(HttpMethod.GET, "/api/decks", null, null, 200).toString().contains(deckId));
        assertEquals(deckId, call(HttpMethod.GET, "/api/decks/mine", ann, null, 200).get(0).get("id").asText());
        assertEquals(8, call(HttpMethod.GET, "/api/decks/" + deckId + "/cards", null, null, 200).size());

        // a public deck cannot be edited or promoted again; the owner copies it instead
        call(HttpMethod.PATCH, "/api/decks/" + deckId, ann, "{\"name\":\"Sneaky change\"}", 409);
        assertEquals(409, upload("PUT", "/api/decks/" + deckId + "/import", ann, deckCsv(8, false), MAPPING, null, null).getStatusCode().value());
        call(HttpMethod.POST, "/api/decks/" + deckId + "/request-global", ann, null, 409);
        JsonNode copy = call(HttpMethod.POST, "/api/decks/" + deckId + "/copy", ann, null, 201);
        assertEquals("PRIVATE", copy.get("visibility").asText());
        call(HttpMethod.PATCH, "/api/decks/" + copy.get("id").asText(), ann, "{\"name\":\"My own copy\"}", 200);
    }

    @Test
    void aPendingPromotionCanBeWithdrawnAndOnlyByItsOwner() throws Exception {
        String ann = login("up_ann7", false), eve = login("up_eve7", false);
        String deckId = uploadDeck(ann, "Changed my mind");
        String requestId = call(HttpMethod.POST, "/api/decks/" + deckId + "/request-global", ann, null, 201).get("id").asText();
        call(HttpMethod.DELETE, "/api/deck-requests/" + requestId, eve, null, 404);
        call(HttpMethod.DELETE, "/api/deck-requests/" + requestId, ann, null, 204);
        call(HttpMethod.GET, "/api/deck-requests/" + requestId, ann, null, 404);
        call(HttpMethod.PATCH, "/api/decks/" + deckId, ann, "{\"name\":\"Editable again\"}", 200);
        // a decided request cannot be withdrawn
        String second = call(HttpMethod.POST, "/api/decks/" + deckId + "/request-global", ann, null, 201).get("id").asText();
        call(HttpMethod.POST, "/api/admin/deck-requests/" + second + "/reject", login("up_boss7", true), "{\"note\":\"no\"}", 200);
        call(HttpMethod.DELETE, "/api/deck-requests/" + second, ann, null, 409);
    }

    @Test
    void deletingADeckWithAnOpenPromotionRemovesTheRequestToo() throws Exception {
        String ann = login("up_ann8", false), boss = login("up_boss8", true);
        String deckId = uploadDeck(ann, "Soon gone");
        String requestId = call(HttpMethod.POST, "/api/decks/" + deckId + "/request-global", ann, null, 201).get("id").asText();
        call(HttpMethod.DELETE, "/api/decks/" + deckId, ann, null, 204);
        call(HttpMethod.GET, "/api/admin/deck-requests/" + requestId, boss, null, 404);
    }

    // ------------------------------------------------------------------------------------------ Flow 1 end to end: import and publish

    private static String wrestlingCsv(int rows, String imageUrl) {
        StringBuilder sb = new StringBuilder("name,image,brand,rank,fight,chest,biceps,height,weight\n");
        for (int i = 1; i <= rows; i++) sb.append("Legend ").append(i).append(',').append(i == 1 ? imageUrl : "").append(",raw,").append(i).append(",4").append(i).append(',')
                .append(30 + i).append(".5,15,").append(60 + i).append(',').append(150 + i).append('\n');
        return sb.toString();
    }

    @Test
    void aCategoryRequestIsApprovedFilledWithStatsAndPublishedAsPrivateOrGlobal() throws Exception {
        String ann = login("fl_ann1", false), eve = login("fl_eve1", false), boss = login("fl_boss1", true);
        String privateId = request(ann, "Private legends").get("id").asText(), globalId = request(ann, "Global legends").get("id").asText();
        JsonNode approvedPrivate = call(HttpMethod.POST, "/api/admin/deck-requests/" + privateId + "/approve", boss, "{\"visibility\":\"PRIVATE\"}", 200);
        JsonNode approvedGlobal = call(HttpMethod.POST, "/api/admin/deck-requests/" + globalId + "/approve", boss, "{\"visibility\":\"PUBLIC\"}", 200);
        String privateDeck = approvedPrivate.get("deckId").asText(), globalDeck = approvedGlobal.get("deckId").asText();

        // publish before import, a bad import, and the importing itself
        call(HttpMethod.POST, "/api/admin/deck-requests/" + globalId + "/publish", boss, null, 409);
        HttpHeaders csv = new HttpHeaders();
        csv.setContentType(MediaType.parseMediaType("text/csv"));
        csv.add("X-Requested-With", "cards");
        csv.add(HttpHeaders.COOKIE, "session=" + boss);
        assertEquals(400, rest.exchange("/api/admin/deck-requests/" + globalId + "/stats", HttpMethod.PUT, new HttpEntity<>(wrestlingCsv(7, ""), csv), String.class).getStatusCode().value());
        assertEquals(400, rest.exchange("/api/admin/deck-requests/" + globalId + "/stats", HttpMethod.PUT,
                new HttpEntity<>(wrestlingCsv(8, "http://not-https.example/a.png"), csv), String.class).getStatusCode().value());
        for (String id : new String[]{privateId, globalId}) {
            ResponseEntity<String> ok = rest.exchange("/api/admin/deck-requests/" + id + "/stats", HttpMethod.PUT, new HttpEntity<>(wrestlingCsv(10, "https://cdn.example.com/l1.jpg"), csv), String.class);
            assertEquals(200, ok.getStatusCode().value(), ok.getBody());
            assertEquals(10, json.readTree(ok.getBody()).get("cardCount").asInt());
        }
        assertEquals(6, jdbc.queryForObject("select count(*) from deck_stats where deck_id = ?::uuid", Integer.class, globalDeck).intValue(), "the six Wrestling stats were copied");
        assertEquals("FEET_INCHES", call(HttpMethod.GET, "/api/admin/deck-requests/" + globalId + "/cards", boss, null, 200).get(0).get("stats").get("height").get("displayValue").asText().contains("'") ? "FEET_INCHES" : "plain");
        // still not live: nobody can read it publicly yet
        call(HttpMethod.GET, "/api/decks/" + globalDeck, eve, null, 404);

        call(HttpMethod.POST, "/api/admin/deck-requests/" + privateId + "/publish", boss, null, 200);
        JsonNode published = call(HttpMethod.POST, "/api/admin/deck-requests/" + globalId + "/publish", boss, null, 200);
        assertTrue(published.get("live").asBoolean());
        assertNotNull(published.get("publishedAt").asText());
        call(HttpMethod.POST, "/api/admin/deck-requests/" + globalId + "/publish", boss, null, 409);                       // already live
        assertEquals(409, rest.exchange("/api/admin/deck-requests/" + globalId + "/stats", HttpMethod.PUT, new HttpEntity<>(wrestlingCsv(10, ""), csv), String.class).getStatusCode().value());

        // global: everyone sees it. private: only the owner (and a game's players)
        assertEquals(10, call(HttpMethod.GET, "/api/decks/" + globalDeck + "/cards", null, null, 200).size());
        assertTrue(call(HttpMethod.GET, "/api/decks", eve, null, 200).toString().contains(globalDeck));
        call(HttpMethod.GET, "/api/decks/" + privateDeck, eve, null, 404);
        call(HttpMethod.GET, "/api/decks/" + privateDeck, null, null, 404);
        assertEquals(10, call(HttpMethod.GET, "/api/decks/" + privateDeck + "/cards", ann, null, 200).size());
        assertFalse(call(HttpMethod.GET, "/api/decks", eve, null, 200).toString().contains(privateDeck));
        assertTrue(call(HttpMethod.GET, "/api/decks", ann, null, 200).toString().contains(privateDeck));
        // the requester sees the result
        JsonNode mine = call(HttpMethod.GET, "/api/deck-requests/" + globalId, ann, null, 200);
        assertTrue(mine.get("live").asBoolean());
        assertEquals(globalDeck, mine.get("deckId").asText());
        // a game with the private deck: its owner can host it, somebody else cannot
        call(HttpMethod.POST, "/api/games", eve, "{\"deckId\":\"" + privateDeck + "\",\"maxPlayers\":2}", 404);
        call(HttpMethod.POST, "/api/games", ann, "{\"deckId\":\"" + privateDeck + "\",\"maxPlayers\":2}", 201);
    }
}
