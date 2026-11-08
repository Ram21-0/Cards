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
import java.util.List;
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

    /** A fake clock and a scheduler the test drives by hand: timers fire when the test says time has passed, so nothing sleeps. */
    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock(Instant.now());
        }

        @Bean
        @Primary
        ManualGameScheduler manualScheduler(TestClock clock) {
            return new ManualGameScheduler(clock);
        }
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired ManualGameScheduler scheduler;
    @Autowired StartupCleanup cleanup;

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

    // ------------------------------------------------------------------------------------------ stage 2: playing

    /** One human at the table, driven by the test over the real endpoints. */
    private final class Human {
        final String name, token;
        Client ws;
        JsonNode view;
        JsonNode gameOver;
        int seen;
        /** Cards that were in this player's views while still unrevealed (so they were theirs alone). */
        final java.util.Set<Integer> privateIds = new java.util.HashSet<>();
        final java.util.Set<Integer> revealedIds = new java.util.HashSet<>();

        Human(String name) throws Exception {
            this.name = name;
            this.token = signIn(name);
        }

        void connect(String code) throws Exception {
            ws = LiveHarness.connect(port, code, token, ORIGIN);
            seen = 0;
            ws.awaitType("lobby");
            seen = 0;
        }

        private void notePrivate(int cardId) {
            if (!revealedIds.contains(cardId)) privateIds.add(cardId);
        }

        /** Reads what arrived since last time. */
        void drain() {
            List<JsonNode> all = ws.all();
            for (; seen < all.size(); seen++) {
                JsonNode m = all.get(seen);
                switch (m.path("type").asText()) {
                    case "view" -> {
                        view = m;
                        m.get("ownHand").forEach(c -> notePrivate(c.get("id").asInt()));
                        if (!m.get("committed").isNull()) notePrivate(m.get("committed").get("id").asInt());
                    }
                    case "events" -> m.get("events").forEach(e -> {
                        if ("revealed".equals(e.get("type").asText())) e.get("cards").forEach(c -> revealedIds.add(c.get("id").asInt()));
                    });
                    case "gameOver" -> gameOver = m;
                    default -> { }
                }
            }
        }

        /** Does the one thing the server is waiting for from this player, if anything. @return whether it acted. */
        boolean act(String code, java.util.Random random) throws Exception {
            drain();
            if (gameOver != null || view == null) return false;
            if (view.get("canChoose").asBoolean()) {
                JsonNode hand = view.get("ownHand");
                int cardId = hand.get(random.nextInt(hand.size())).get("id").asInt();
                JsonNode answer = call(HttpMethod.POST, "/api/games/" + code + "/choose", token, "{\"cardId\":" + cardId + "}", 200);
                assertEquals("view", answer.get("type").asText());
                view = null;                                                      // wait for the pushed view
                return true;
            }
            if (view.get("canCall").asBoolean()) {
                List<String> keys = new java.util.ArrayList<>();
                view.get("ownHand").get(0).get("stats").fieldNames().forEachRemaining(keys::add);
                call(HttpMethod.POST, "/api/games/" + code + "/call", token, "{\"stat\":\"" + keys.get(random.nextInt(keys.size())) + "\"}", 200);
                view = null;
                return true;
            }
            return false;
        }
    }

    private String startThreeSeatGame(Human ann, Human bob, Integer turnTimeoutSeconds, Integer maxRounds) throws Exception {
        String deckId = jdbc.queryForObject("select id::text from decks where name = 'Wrestling'", String.class);
        String options = "{\"maxRounds\":" + (maxRounds == null ? 30 : maxRounds) + ",\"chooseThreshold\":3"
                + (turnTimeoutSeconds == null ? "" : ",\"turnTimeoutSeconds\":" + turnTimeoutSeconds) + "}";
        JsonNode game = call(HttpMethod.POST, "/api/games", ann.token,
                "{\"deckId\":\"" + deckId + "\",\"maxPlayers\":3,\"options\":" + options + "}", 201);
        String code = game.get("joinCode").asText();
        call(HttpMethod.POST, "/api/games/" + code + "/join", bob.token, null, 200);
        call(HttpMethod.POST, "/api/games/" + code + "/cpu", ann.token, "{\"difficulty\":\"hard\"}", 200);
        ann.connect(code);
        bob.connect(code);
        call(HttpMethod.POST, "/api/games/" + code + "/start", ann.token, null, 200);
        return code;
    }

    @Test
    void twoHumansAndACpuPlayAFullGameOverTheRealEndpointsAndTheResultIsSaved() throws Exception {
        Human ann = new Human("it_ann"), bob = new Human("it_bob");
        String code = startThreeSeatGame(ann, bob, null, 30);
        java.util.Random random = new java.util.Random(7);

        boolean reconnected = false;
        int actions = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while ((ann.gameOver == null || bob.gameOver == null) && System.nanoTime() < deadline) {
            boolean acted = ann.act(code, random) | bob.act(code, random);
            if (acted) actions++;
            if (!acted) {
                Thread.sleep(5);
                ann.drain();
                bob.drain();
                scheduler.advance(java.time.Duration.ofSeconds(1));                  // lets a clash race or CPU delay run out
            }
            if (!reconnected && actions == 1) {
                reconnected = true;
                reconnectMidGame(ann, code);
            }
        }
        assertTrue(ann.gameOver != null && bob.gameOver != null, "the game ended for both players");
        assertTrue(reconnected, "the game lasted long enough to test a reconnect");

        // the result both players were told
        JsonNode placements = ann.gameOver.get("placements");
        assertEquals(bob.gameOver.get("placements"), placements);
        assertEquals(3, placements.size());
        assertTrue(placements.toString().contains(":1"), "someone won");
        assertEquals("FINISHED", ann.gameOver.get("game").get("status").asText());
        assertEquals(1, ann.gameOver.get("mySeat").asInt());
        assertEquals(2, bob.gameOver.get("mySeat").asInt());

        // ...and what was saved
        assertEquals(2, jdbc.queryForObject("select status from games where join_code = ?", Integer.class, code).intValue());
        assertTrue(jdbc.queryForObject("select finished_at is not null and rounds > 0 from games where join_code = ?", Boolean.class, code));
        for (int seat = 1; seat <= 3; seat++) {
            Integer saved = jdbc.queryForObject("select placement from game_players gp join games g on g.id = gp.game_id where g.join_code = ? and seat = ?",
                    Integer.class, code, seat);
            assertEquals(placements.get(String.valueOf(seat)).asInt(), saved.intValue(), "placement of seat " + seat);
        }

        // hidden information: a card is only ever in one player's views until it is revealed to everyone
        java.util.Set<Integer> overlap = new java.util.HashSet<>(ann.privateIds);
        overlap.retainAll(bob.privateIds);
        assertTrue(overlap.isEmpty(), "cards that both players saw while unrevealed: " + overlap);
        assertFalse(ann.privateIds.isEmpty() && bob.privateIds.isEmpty(), "the check saw some cards");
        assertEquals(ann.revealedIds, bob.revealedIds, "both players were shown the same reveals");

        // a finished game: no more actions, and a late connection gets the final lobby but no view
        call(HttpMethod.POST, "/api/games/" + code + "/call", ann.token, "{\"stat\":\"rank\"}", 409);
        Client late = LiveHarness.connect(port, code, bob.token, ORIGIN);
        JsonNode lobby = late.awaitType("lobby");
        assertEquals("FINISHED", lobby.get("game").get("status").asText());
        assertEquals(1, lobby.get("game").get("seats").get(0).get("placement").asInt() > 0 ? 1 : 0);
        Thread.sleep(100);
        assertTrue(late.all().stream().noneMatch(m -> "view".equals(m.path("type").asText())));
        late.close();
    }

    /** Drops ann's socket and opens a new one: the server must rebuild exactly her current view. */
    private void reconnectMidGame(Human ann, String code) throws Exception {
        Thread.sleep(150);                                   // let the pushes of the last action arrive
        ann.drain();
        JsonNode before = ann.view;
        ann.ws.close();
        assertTrue(ann.ws.awaitClosed(5_000));
        ann.connect(code);
        ann.view = null;
        Client fresh = ann.ws;
        assertEquals("IN_PROGRESS", fresh.all().get(0).get("game").get("status").asText(), "the lobby comes first");
        JsonNode after = fresh.awaitType("view");
        ann.seen = 0;
        ann.drain();
        if (before != null) {
            assertEquals(before.get("round"), after.get("round"));
            assertEquals(before.get("phase"), after.get("phase"));
            assertEquals(before.get("handSizes"), after.get("handSizes"));
            assertEquals(before.get("ownHand"), after.get("ownHand"));
            assertEquals(before.get("canCall"), after.get("canCall"));
        }
    }

    @Test
    void aTurnThatRunsOutOfTimeIsPlayedByTheServerAndEveryoneSeesIt() throws Exception {
        Human ann = new Human("to_ann"), bob = new Human("to_bob");
        startThreeSeatGame(ann, bob, 5, 30);
        ann.drain();
        bob.drain();

        scheduler.advance(java.time.Duration.ofSeconds(4));
        Thread.sleep(100);
        assertTrue(ann.ws.all().stream().noneMatch(LiveGameIntegrationTest::hasAutoEvent), "nothing has timed out yet");
        scheduler.advance(java.time.Duration.ofSeconds(1));

        for (Human h : new Human[]{ann, bob}) {
            JsonNode events = h.ws.await(LiveGameIntegrationTest::hasAutoEvent, 5_000);
            assertEquals("events", events.get("type").asText(), h.name + " is told the move was made by the clock");
        }
    }

    private static boolean hasAutoEvent(JsonNode message) {
        if (!"events".equals(message.path("type").asText())) return false;
        for (JsonNode e : message.get("events")) if (e.path("auto").asBoolean(false)) return true;
        return false;
    }

    private static boolean hasEvent(JsonNode message, String type) {
        if (!"events".equals(message.path("type").asText())) return false;
        for (JsonNode e : message.get("events")) if (type.equals(e.get("type").asText())) return true;
        return false;
    }

    @Test
    void someoneElsesSeatCannotBeActedForAndOutsidersAreRefused() throws Exception {
        Human ann = new Human("sx_ann"), bob = new Human("sx_bob");
        String code = startThreeSeatGame(ann, bob, null, 30);
        Thread.sleep(150);
        ann.drain();
        bob.drain();

        call(HttpMethod.POST, "/api/games/" + code + "/call", bob.token, "{\"stat\":\"nonsense\"}", 400);
        call(HttpMethod.POST, "/api/games/" + code + "/call", ann.token, "{\"stat\":\"nonsense\"}", 400);
        call(HttpMethod.POST, "/api/games/" + code + "/choose", ann.token, "{\"cardId\":999999}", ann.view.get("canChoose").asBoolean() ? 400 : 409);
        call(HttpMethod.POST, "/api/games/" + code + "/call", signIn("sx_out"), "{\"stat\":\"rank\"}", 403);
        ResponseEntity<String> anonymous = rest.postForEntity("/api/games/" + code + "/call", jsonEntity("{\"stat\":\"rank\"}", null), String.class);
        assertEquals(401, anonymous.getStatusCode().value());

        // a human who may not call right now (not the caller of a normal round) is refused with 403
        boolean annCan = ann.view.get("canCall").asBoolean(), bobCan = bob.view.get("canCall").asBoolean();
        if (annCan != bobCan) {
            Human other = annCan ? bob : ann;
            call(HttpMethod.POST, "/api/games/" + code + "/call", other.token, "{\"stat\":\"rank\"}", 403);
        }
    }

    @Test
    void gamesThatWereRunningWhenTheServerRestartedAreMarkedAbandoned() throws Exception {
        Human ann = new Human("rs_ann"), bob = new Human("rs_bob");
        String code = startThreeSeatGame(ann, bob, null, 30);
        assertEquals(1, jdbc.queryForObject("select status from games where join_code = ?", Integer.class, code).intValue());

        cleanup.run(null);                                   // what happens at startup

        assertEquals(3, jdbc.queryForObject("select status from games where join_code = ?", Integer.class, code).intValue());
    }
}
