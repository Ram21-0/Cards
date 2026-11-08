package com.cards.live;

import com.cards.TestClock;
import com.cards.live.LiveHarness.Client;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real thing: a running server, real HTTP + WebSocket clients, real Postgres.
 *
 * <p>The test creates its OWN scratch database (cards_it_xxxx) on a local Postgres and drops it afterwards, and it
 * refuses to run against any other database. It is skipped when no Postgres is reachable. Defaults match a Homebrew
 * install on this machine; override with TEST_PG_HOST / TEST_PG_PORT / TEST_PG_USER / TEST_PG_PASSWORD.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.cards.live.LiveGameIntegrationTest#postgresAvailable")
@Import(LiveGameIntegrationTest.TestBeans.class)
class LiveGameIntegrationTest {

    static final String ORIGIN = "http://localhost:8081";
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

    /** A fake clock (so timers are deterministic in later tests) and nothing else yet. */
    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock(Instant.now());
        }
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;

    // ------------------------------------------------------------------------------------------ helpers

    @BeforeEach
    void safetyAndSeed() {
        String url = dataSource.toString() + jdbc.queryForObject("select current_database()", String.class);
        assertEquals(DB, jdbc.queryForObject("select current_database()", String.class),
                "refusing to run: not connected to the scratch database (" + url + ")");
        Integer cards = jdbc.queryForObject("select count(*) from cards", Integer.class);
        if (cards != null && cards == 0) {
            jdbc.update("""
                    insert into cards (deck_id, name, stats)
                    select d.id, 'IT-' || g, jsonb_build_object('rank', g, 'fight', 10 + g, 'chest', 40, 'biceps', 15, 'height', 70, 'weight', 200 + g)
                    from decks d, generate_series(1, 12) g where d.name = 'Wrestling'""");
        }
    }

    /** A signed-in test user (dev login): the session token. */
    private String signIn(String username) throws Exception {
        ResponseEntity<String> r = rest.postForEntity("/api/dev/login", jsonEntity("{\"username\":\"" + username + "\"}", null), String.class);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        String setCookie = r.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.substring("session=".length(), setCookie.indexOf(';'));
    }

    private static HttpEntity<String> jsonEntity(String body, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Requested-With", "cards");
        if (token != null) h.add(HttpHeaders.COOKIE, "session=" + token);
        return new HttpEntity<>(body, h);
    }

    private JsonNode call(HttpMethod method, String path, String token, String body, int expectedStatus) throws Exception {
        ResponseEntity<String> r = rest.exchange(path, method, jsonEntity(body, token), String.class);
        assertEquals(expectedStatus, r.getStatusCode().value(), method + " " + path + " -> " + r.getBody());
        return r.getBody() == null || r.getBody().isBlank() ? null : json.readTree(r.getBody());
    }

    private String createGame(String token, int maxPlayers) throws Exception {
        String deckId = jdbc.queryForObject("select id::text from decks where name = 'Wrestling'", String.class);
        JsonNode game = call(HttpMethod.POST, "/api/games", token, "{\"deckId\":\"" + deckId + "\",\"maxPlayers\":" + maxPlayers + "}", 201);
        return game.get("joinCode").asText();
    }

    private static int seats(JsonNode lobby) {
        return lobby.get("game").get("seats").size();
    }

    // ------------------------------------------------------------------------------------------ stage 1: the lobby

    @Test
    void theLobbyIsLiveForEveryoneInIt() throws Exception {
        String ann = signIn("ann"), bob = signIn("bob");
        String code = createGame(ann, 3);

        Client annWs = LiveHarness.connect(port, code, ann, ORIGIN);
        JsonNode first = annWs.awaitType("lobby");
        assertEquals(1, first.get("mySeat").asInt());
        assertEquals(1, seats(first));
        assertEquals(1, first.get("game").get("hostSeat").asInt());
        assertEquals("WAITING", first.get("game").get("status").asText());

        call(HttpMethod.POST, "/api/games/" + code + "/join", bob, null, 200);
        JsonNode afterJoin = annWs.awaitType("lobby");
        assertEquals(2, seats(afterJoin), "ann is told at once when bob joins");
        assertEquals("bob", afterJoin.get("game").get("seats").get(1).get("username").asText());

        Client bobWs = LiveHarness.connect(port, code, bob, ORIGIN);
        JsonNode bobFirst = bobWs.awaitType("lobby");
        assertEquals(2, bobFirst.get("mySeat").asInt());
        assertEquals(2, seats(bobFirst));

        call(HttpMethod.POST, "/api/games/" + code + "/cpu", ann, "{\"difficulty\":\"hard\"}", 200);
        for (Client c : new Client[]{annWs, bobWs}) {
            JsonNode lobby = c.awaitType("lobby");
            assertEquals(3, seats(lobby));
            assertEquals("CPU", lobby.get("game").get("seats").get(2).get("kind").asText());
            assertEquals("hard", lobby.get("game").get("seats").get(2).get("difficulty").asText());
        }

        call(HttpMethod.POST, "/api/games/" + code + "/leave", bob, null, 200);
        assertTrue(bobWs.awaitClosed(5_000), "bob's socket is closed when he leaves");
        JsonNode afterLeave = annWs.awaitType("lobby");
        assertEquals(2, seats(afterLeave));
        assertEquals(1, afterLeave.get("game").get("seats").get(0).get("seat").asInt());
        assertEquals(3, afterLeave.get("game").get("seats").get(1).get("seat").asInt(), "seat numbers keep their gaps until the game starts");

        call(HttpMethod.POST, "/api/games/" + code + "/join", bob, null, 200);                 // he can come back
        assertEquals(3, seats(annWs.awaitType("lobby")));
        annWs.close();
    }

    @Test
    void onlyTheHostCanAddRemoveAndStartAndHumansCannotBeRemoved() throws Exception {
        String ann = signIn("host1"), bob = signIn("guest1"), outsider = signIn("outsd1");
        String code = createGame(ann, 4);
        call(HttpMethod.POST, "/api/games/" + code + "/join", bob, null, 200);

        call(HttpMethod.POST, "/api/games/" + code + "/cpu", bob, "{\"difficulty\":\"easy\"}", 403);
        call(HttpMethod.POST, "/api/games/" + code + "/start", bob, null, 403);
        call(HttpMethod.POST, "/api/games/" + code + "/cpu", ann, "{\"difficulty\":\"genius\"}", 400);
        call(HttpMethod.POST, "/api/games/" + code + "/cpu", ann, "{\"difficulty\":\"easy\"}", 200);
        call(HttpMethod.DELETE, "/api/games/" + code + "/seats/3", bob, null, 403);
        call(HttpMethod.DELETE, "/api/games/" + code + "/seats/2", ann, null, 400);          // a human
        call(HttpMethod.DELETE, "/api/games/" + code + "/seats/4", ann, null, 404);          // empty
        JsonNode removed = call(HttpMethod.DELETE, "/api/games/" + code + "/seats/3", ann, null, 200);
        assertEquals(2, removed.get("seats").size());
        call(HttpMethod.POST, "/api/games/" + code + "/leave", outsider, null, 409);          // not in the game

        // signed out
        ResponseEntity<String> anonymous = rest.postForEntity("/api/games/" + code + "/start", jsonEntity(null, null), String.class);
        assertEquals(401, anonymous.getStatusCode().value());
    }

    @Test
    void theHostLeavingPassesTheGameOnAndTheLastHumanLeavingAbandonsIt() throws Exception {
        String ann = signIn("hostA"), bob = signIn("hostB");
        String code = createGame(ann, 3);
        call(HttpMethod.POST, "/api/games/" + code + "/join", bob, null, 200);

        JsonNode afterHostLeft = call(HttpMethod.POST, "/api/games/" + code + "/leave", ann, null, 200);
        assertEquals(1, afterHostLeft.get("seats").size());
        assertEquals(2, afterHostLeft.get("hostSeat").asInt(), "bob (seat 2) is the host now");
        call(HttpMethod.POST, "/api/games/" + code + "/cpu", bob, "{\"difficulty\":\"easy\"}", 200);

        JsonNode abandoned = call(HttpMethod.POST, "/api/games/" + code + "/leave", bob, null, 200);
        assertEquals("ABANDONED", abandoned.get("status").asText());
    }

    @Test
    void startingNeedsTwoSeatsThenRenumbersAndPushesStartedAndEachPlayersView() throws Exception {
        String ann = signIn("starter");
        String code = createGame(ann, 3);
        call(HttpMethod.POST, "/api/games/" + code + "/start", ann, null, 409);              // alone

        call(HttpMethod.POST, "/api/games/" + code + "/cpu", ann, "{\"difficulty\":\"easy\"}", 200);
        call(HttpMethod.POST, "/api/games/" + code + "/cpu", ann, "{\"difficulty\":\"medium\"}", 200);
        call(HttpMethod.DELETE, "/api/games/" + code + "/seats/2", ann, null, 200);         // leaves a gap: seats 1 and 3
        Client annWs = LiveHarness.connect(port, code, ann, ORIGIN);
        assertEquals(2, seats(annWs.awaitType("lobby")));

        JsonNode started = call(HttpMethod.POST, "/api/games/" + code + "/start", ann, null, 200);
        assertEquals("IN_PROGRESS", started.get("status").asText());
        assertEquals(2, started.get("seats").get(1).get("seat").asInt(), "the CPU moved from seat 3 down to seat 2");

        JsonNode startedMsg = annWs.awaitType("started");
        assertEquals("IN_PROGRESS", startedMsg.get("game").get("status").asText());
        assertEquals(1, startedMsg.get("mySeat").asInt());
        JsonNode view = annWs.awaitType("view");
        assertEquals(1, view.get("seat").asInt());
        assertEquals(2, view.get("handSizes").size());
        assertEquals(12, view.get("handSizes").get("1").asInt() + view.get("handSizes").get("2").asInt(), "every card of the deck is dealt");
        assertTrue(view.get("ownHand").size() <= 1 || view.get("canChoose").asBoolean());

        assertEquals("IN_PROGRESS", jdbc.queryForObject("select case status when 1 then 'IN_PROGRESS' else status::text end from games where join_code = ?", String.class, code));
        assertEquals("1,2", jdbc.queryForObject("select string_agg(seat::text, ',' order by seat) from game_players gp join games g on g.id = gp.game_id where g.join_code = ?", String.class, code));
        assertFalse(jdbc.queryForObject("select started_at is null from games where join_code = ?", Boolean.class, code));

        call(HttpMethod.POST, "/api/games/" + code + "/start", ann, null, 409);              // already started
        call(HttpMethod.POST, "/api/games/" + code + "/cpu", ann, "{\"difficulty\":\"easy\"}", 409);

        // a reconnect gets the lobby (now IN_PROGRESS) and the current view straight away
        annWs.close();
        Client again = LiveHarness.connect(port, code, ann, ORIGIN);
        assertEquals("IN_PROGRESS", again.awaitType("lobby").get("game").get("status").asText());
        assertEquals(1, again.awaitType("view").get("seat").asInt());
        again.close();
    }

    // ------------------------------------------------------------------------------------------ the handshake

    @Test
    void theHandshakeChecksSessionSeatGameAndOrigin() throws Exception {
        String ann = signIn("hs_ann"), stranger = signIn("hs_str");
        String code = createGame(ann, 3);

        assertEquals(101, LiveHarness.handshakeStatus(port, code, ann, ORIGIN), "seated player, allowed origin");
        assertEquals(401, LiveHarness.handshakeStatus(port, code, null, ORIGIN), "no session");
        assertEquals(401, LiveHarness.handshakeStatus(port, code, "not-a-real-token", ORIGIN), "unknown session");
        assertEquals(403, LiveHarness.handshakeStatus(port, code, stranger, ORIGIN), "signed in but not seated");
        assertEquals(404, LiveHarness.handshakeStatus(port, "NOPE12", ann, ORIGIN), "unknown game");
        assertEquals(403, LiveHarness.handshakeStatus(port, code, ann, "http://evil.example"), "an origin that is not the frontend");
    }

    @Test
    void aPingIsAnsweredAndTheJoinCodeInThePathIsCaseInsensitive() throws Exception {
        String ann = signIn("ping_ann");
        String code = createGame(ann, 2);
        Client ws = LiveHarness.connect(port, code.toLowerCase(), ann, ORIGIN);
        ws.awaitType("lobby");
        ws.send("{\"type\":\"ping\"}");
        assertEquals("pong", ws.awaitType("pong").get("type").asText());
        ws.send("this is not json");
        assertEquals("error", ws.awaitType("error").get("type").asText());
        ws.close();
    }
}
