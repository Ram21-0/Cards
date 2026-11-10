package com.cards.live;

import com.cards.TestClock;
import com.cards.common.Card;
import com.cards.common.ConflictException;
import com.cards.common.DifficultyLevel;
import com.cards.common.ForbiddenException;
import com.cards.common.GameStatus;
import com.cards.common.BadRequestException;
import com.cards.common.Player;
import com.cards.game.GameEngine;
import com.cards.game.GameSettings;
import com.cards.live.LiveTestKit.FakeSocket;
import com.cards.service.db.GameRepository;
import com.cards.service.service.GameDetails;
import com.cards.service.service.GameService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cards.live.LiveTestKit.CODE;
import static com.cards.live.LiveTestKit.FRONTEND;
import static com.cards.live.LiveTestKit.card;
import static com.cards.live.LiveTestKit.details;
import static com.cards.live.LiveTestKit.human;
import static com.cards.live.LiveTestKit.humans;
import static com.cards.live.LiveTestKit.json;
import static com.cards.live.LiveTestKit.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Running a game: commands, timers, absent players, hidden information and saving the result. Fake clock, fake sockets. */
class LivePlayTest {

    private static final GameSettings NO_CHOOSE = new GameSettings(500, 0, 5, null);
    private static final GameSettings CHOOSE = new GameSettings(500, 3, 5, null);
    private static final GameSettings TIMED = new GameSettings(500, 0, 5, 10);

    private final TestClock clock = TestClock.atNow();
    private final ManualGameScheduler scheduler = new ManualGameScheduler(clock);
    private final GameHub hub = new GameHub(clock, 90);
    private final GameRegistry registry = new GameRegistry();
    private final LiveMessages messages = new LiveMessages(json(), clock, FRONTEND);
    private final GameRepository repository = mock(GameRepository.class);
    private final GameService games = mock(GameService.class);
    private final LivePlay play = new LivePlay(registry, hub, messages, scheduler, repository, games, clock, 15);
    private final LiveGameService service = new LiveGameService(games, hub, registry, messages, play);
    private final Player ann = player("ann"), bob = player("bob");

    private LiveGame live;
    private FakeSocket forAnn, forBob;

    /** Starts a running game for ann (seat 1) and bob (seat 2) with the given hands; each is connected unless said otherwise. */
    private void startGame(GameSettings settings, List<List<Card>> hands, boolean annConnected, boolean bobConnected) {
        GameEngine engine = LiveTestKit.engine(clock, settings, humans(2), hands);
        var seats = List.of(human(1, ann), human(2, bob));
        GameDetails running = details(GameStatus.IN_PROGRESS, ann, seats);
        when(games.get(CODE)).thenReturn(running);
        live = new LiveGame(CODE, running.game().id(), engine, LiveTestKit.DECK, seats);
        registry.put(live);
        if (annConnected) forAnn = connect(ann);
        if (bobConnected) forBob = connect(bob);
        play.begin(live, running);
    }

    private FakeSocket connect(Player p) {
        FakeSocket socket = new FakeSocket();
        hub.add(socket.connect(CODE, p, clock.instant()));
        return socket;
    }

    private static List<JsonNode> events(FakeSocket socket) throws Exception {
        List<JsonNode> all = new java.util.ArrayList<>();
        for (String text : socket.ofType("events")) json().readTree(text).get("events").forEach(all::add);
        return all;
    }

    private static List<String> eventTypes(FakeSocket socket) throws Exception {
        return events(socket).stream().map(e -> e.get("type").asText()).toList();
    }

    private static JsonNode lastView(FakeSocket socket) throws Exception {
        List<String> views = socket.ofType("view");
        return json().readTree(views.get(views.size() - 1));
    }

    // ------------------------------------------------------------------------------------------ the start

    @Test
    void beginningTellsEachPlayerStartedThenEventsThenTheirOwnView() throws Exception {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 9, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 3, 3), card(2, 2, 4, 4))), true, true);

        List<String> types = forAnn.sent.stream().map(s -> parseType(s)).toList();
        assertEquals(List.of("started", "events", "view"), types);
        assertEquals(List.of("roundStarted"), eventTypes(forAnn));
        JsonNode view = lastView(forAnn);
        assertEquals(1, view.get("seat").asInt());
        assertEquals("AWAIT_CALL", view.get("phase").asText());
        assertTrue(view.get("canCall").asBoolean());
        assertFalse(lastView(forBob).get("canCall").asBoolean());
    }

    private static String parseType(String text) {
        try {
            return json().readTree(text).get("type").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------ hidden information

    @Test
    void nobodyIsToldAboutACardOrItsValuesBeforeItIsRevealed() throws Exception {
        // 2 cards each and CHOOSE on: seat 1 sees both of its cards; seat 2 must learn nothing about them until the reveal.
        startGame(CHOOSE, List.of(List.of(card(1, 1, 9, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 3, 3), card(2, 2, 4, 4))), true, true);
        assertEquals(2, lastView(forAnn).get("ownHand").size(), "the caller may choose, so it sees its whole hand");
        assertEquals(1, lastView(forBob).get("ownHand").size(), "the other player only has its top card");

        service.choose(CODE, ann, 1002);                         // seat 1 commits its second card
        assertTrue(lastView(forAnn).get("committed") != null && !lastView(forAnn).get("committed").isNull());
        service.call(CODE, ann, "power");                        // the stat is public; the cards are not

        assertNoSeat1CardIn(forBob);
        assertFalse(lastView(forBob).get("ownHand").isEmpty());
        // seat 2 chooses its second card -> now everything is revealed to both
        service.choose(CODE, bob, 2002);
        assertEquals(List.of("revealed"), eventTypes(forBob).stream().filter("revealed"::equals).toList());
        JsonNode revealed = events(forBob).stream().filter(e -> "revealed".equals(e.get("type").asText())).findFirst().orElseThrow();
        assertEquals("S1-2", revealed.get("cards").get("1").get("name").asText());
        assertEquals("S2-2", revealed.get("cards").get("2").get("name").asText());
        assertEquals(revealed, events(forAnn).stream().filter(e -> "revealed".equals(e.get("type").asText())).findFirst().orElseThrow(),
                "both players get the identical reveal");
    }

    @Test
    void theOwnPileListsOnlyMyCardsAndNeverSomeoneElses() throws Exception {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 9, 1), card(1, 2, 5, 5), card(1, 3, 4, 4)), List.of(card(2, 1, 3, 3), card(2, 2, 4, 4))), true, true);

        assertEquals(List.of("S1-2", "S1-3"), pileNames(forAnn), "seat 1's top card is in play (committed), the others follow");
        assertEquals(List.of("S2-2"), pileNames(forBob));
        assertTrue(forBob.sent.stream().noneMatch(m -> m.contains("S1-")), "bob never receives a seat 1 card, pile included");
        assertTrue(forAnn.sent.stream().noneMatch(m -> m.contains("S2-")), "ann never receives a seat 2 card, pile included");
        assertEquals(Set.of("id", "name"), fieldNames(lastView(forAnn).get("ownPile").get(0)), "names and ids only, no stats");
    }

    private static List<String> pileNames(FakeSocket socket) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        lastView(socket).get("ownPile").forEach(c -> names.add(c.get("name").asText()));
        return names;
    }

    private static java.util.Set<String> fieldNames(JsonNode node) {
        java.util.Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void assertNoSeat1CardIn(FakeSocket socket) {
        for (String text : socket.sent) {
            assertFalse(text.contains("S1-"), "a seat 1 card name leaked: " + text);
            assertFalse(text.matches("(?s).*\"id\":100[12].*"), "a seat 1 card id leaked: " + text);
        }
    }

    @Test
    void anEventNeverHoldsACardExceptReveal() throws Exception {
        startGame(CHOOSE, List.of(List.of(card(1, 1, 9, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 3, 3), card(2, 2, 4, 4))), true, true);
        service.choose(CODE, ann, 1001);
        service.call(CODE, ann, "power");
        service.choose(CODE, bob, 2001);
        for (JsonNode e : events(forAnn)) {
            if (!"revealed".equals(e.get("type").asText())) {
                assertFalse(e.toString().contains("S1-") || e.toString().contains("S2-"), "card in event " + e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ commands

    @Test
    void aCallIsAppliedPushedToEveryoneAndAnsweredWithTheCallersView() throws Exception {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 3, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 9, 3), card(2, 2, 4, 4))), true, true);

        ViewMessage answer = service.call(CODE, ann, "power");

        assertEquals(1, answer.seat());
        assertEquals(List.of("roundStarted", "statCalled", "revealed", "roundWon", "roundStarted"), eventTypes(forBob));
        assertEquals(eventTypes(forAnn), eventTypes(forBob));
        assertEquals(2, lastView(forBob).get("callerSeat").asInt(), "the winner calls next");
        assertTrue(lastView(forBob).get("canCall").asBoolean());
    }

    @Test
    void refusedCommandsMapToBadRequestForbiddenAndConflict() {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 3, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 9, 3), card(2, 2, 4, 4))), true, true);

        assertThrows(ForbiddenException.class, () -> service.call(CODE, bob, "power"));            // not the caller
        assertThrows(BadRequestException.class, () -> service.call(CODE, ann, "charisma"));         // unknown stat
        assertThrows(ConflictException.class, () -> service.choose(CODE, ann, 1001));               // not waiting for a card choice
        assertThrows(Exception.class, () -> service.call(CODE, player("eve"), "power"));            // not seated
        assertThrows(ForbiddenException.class, () -> service.call(CODE, player("eve"), "power"));
        assertThrows(ConflictException.class, () -> service.call("ZZZZZZ", ann, "power"));
    }

    @Test
    void aGameThatIsNotRunningIsAConflict() {
        when(games.get("WAITIN")).thenReturn(details(GameStatus.WAITING, ann, List.of(human(1, ann))));
        assertThrows(ConflictException.class, () -> service.call("WAITIN", ann, "power"));
    }

    // ------------------------------------------------------------------------------------------ timers

    @Test
    void whenTheTurnTimerRunsOutTheServerCallsAtRandom() throws Exception {
        startGame(TIMED, List.of(List.of(card(1, 1, 3, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 9, 3), card(2, 2, 4, 4))), true, true);
        assertEquals(clock.instant().plusSeconds(10), lastView(forAnn).get("deadline") == null || lastView(forAnn).get("deadline").isNull()
                ? null : java.time.Instant.parse(lastView(forAnn).get("deadline").asText()));

        scheduler.advance(Duration.ofSeconds(9));
        assertFalse(eventTypes(forAnn).contains("statCalled"));
        scheduler.advance(Duration.ofSeconds(1));

        JsonNode call = events(forAnn).stream().filter(e -> "statCalled".equals(e.get("type").asText())).findFirst().orElseThrow();
        assertTrue(call.get("auto").asBoolean());
        assertEquals(eventTypes(forAnn), eventTypes(forBob));
    }

    @Test
    void anUncontestedClashRaceIsEndedByTheFiveSecondTimer() throws Exception {
        // equal first cards on both stats -> a clash; nobody calls
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 5, 5), card(1, 2, 8, 2)), List.of(card(2, 1, 5, 5), card(2, 2, 7, 3))), true, true);
        service.call(CODE, ann, "power");
        assertTrue(eventTypes(forAnn).contains("clashStarted"));
        assertEquals("CLASH_AWAIT_CALL", lastView(forAnn).get("phase").asText());

        scheduler.advance(Duration.ofSeconds(4));
        assertEquals("CLASH_AWAIT_CALL", lastView(forAnn).get("phase").asText(), "still racing");
        scheduler.advance(Duration.ofSeconds(1));

        JsonNode auto = events(forAnn).stream().filter(e -> "statCalled".equals(e.get("type").asText())).reduce((a, b) -> b).orElseThrow();
        assertTrue(auto.get("auto").asBoolean());
        assertTrue(auto.get("seat").isNull(), "nobody called: the server picked a stat");
    }

    @Test
    void anAbsentCallerIsTimedOutAfterTheGraceAndNotBefore() throws Exception {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 3, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 9, 3), card(2, 2, 4, 4))), false, true);

        scheduler.advance(Duration.ofSeconds(14));
        assertFalse(eventTypes(forBob).contains("statCalled"), "within the grace");
        scheduler.advance(Duration.ofSeconds(1));

        JsonNode call = events(forBob).stream().filter(e -> "statCalled".equals(e.get("type").asText())).findFirst().orElseThrow();
        assertTrue(call.get("auto").asBoolean());
        assertEquals(1, call.get("seat").asInt());
    }

    @Test
    void reconnectingWithinTheGraceCancelsTheTimeoutAndGetsTheCurrentView() throws Exception {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 3, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 9, 3), card(2, 2, 4, 4))), false, true);
        scheduler.advance(Duration.ofSeconds(10));

        FakeSocket again = connect(ann);                        // the connection part of LiveGameService.onConnect
        service.onConnect(new Connection(again.session, CODE, ann, clock.instant()));
        scheduler.advance(Duration.ofSeconds(60));

        assertFalse(eventTypes(forBob).contains("statCalled"));
        assertEquals("AWAIT_CALL", lastView(again).get("phase").asText());
        assertTrue(lastView(again).get("canCall").asBoolean());
    }

    @Test
    void aPlayerWhoDropsOnTheirTurnIsTimedOutAfterTheGraceFromTheDisconnect() throws Exception {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 3, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 9, 3), card(2, 2, 4, 4))), true, true);
        scheduler.advance(Duration.ofSeconds(100));              // connected: nothing happens however long they think
        assertFalse(eventTypes(forBob).contains("statCalled"));

        Connection annConnection = hub.connections(CODE).stream().filter(c -> c.player().id().equals(ann.id())).findFirst().orElseThrow();
        hub.remove(annConnection);
        service.onDisconnect(annConnection);
        scheduler.advance(Duration.ofSeconds(14));
        assertFalse(eventTypes(forBob).contains("statCalled"));
        scheduler.advance(Duration.ofSeconds(1));
        assertTrue(eventTypes(forBob).contains("statCalled"));
    }

    @Test
    void anAbsentPlayerWhoseTurnItIsNotIsLeftAlone() throws Exception {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 3, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 9, 3), card(2, 2, 4, 4))), true, false);
        scheduler.advance(Duration.ofSeconds(120));
        assertEquals(List.of("roundStarted"), eventTypes(forAnn), "bob is absent but it is ann's turn: nobody times out");
        assertEquals(0, scheduler.runDue());
    }

    @Test
    void theAbsentPlayersTurnIsTimedOutOnceTheirTurnArrives() throws Exception {
        // ann calls and wins nothing special; bob (absent from the start) becomes the caller after winning
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 3, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 9, 3), card(2, 2, 4, 4))), true, false);
        scheduler.advance(Duration.ofSeconds(100));
        service.call(CODE, ann, "power");                        // bob's 9 beats ann's 3, so bob calls next
        assertEquals(2, lastView(forAnn).get("callerSeat").asInt());

        scheduler.advance(Duration.ofSeconds(14));
        assertEquals(1, events(forAnn).stream().filter(e -> "statCalled".equals(e.get("type").asText())).count(), "the grace starts when the turn does");
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(2, events(forAnn).stream().filter(e -> "statCalled".equals(e.get("type").asText())).count());
    }

    // ------------------------------------------------------------------------------------------ the end

    @Test
    void whenTheGameEndsTheResultIsSavedPushedAndTheGameLeavesMemory() throws Exception {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 9, 1)), List.of(card(2, 1, 3, 3))), true, true);
        GameDetails finished = details(GameStatus.FINISHED, ann, List.of(human(1, ann), human(2, bob)));
        when(games.get(CODE)).thenReturn(finished);

        service.call(CODE, ann, "power");

        verify(repository).finish(eq(live.gameId()), eq(Map.of(1, 1, 2, 2)), eq(1), any());
        assertEquals(0, registry.size());
        JsonNode over = json().readTree(forBob.ofType("gameOver").get(0));
        assertEquals(1, over.get("placements").get("1").asInt());
        assertEquals(2, over.get("placements").get("2").asInt());
        assertFalse(over.get("endedByRoundCap").asBoolean());
        assertEquals(2, over.get("mySeat").asInt());
        assertEquals("gameOver", parseType(forBob.last()), "the very last message");
        assertEquals(0, scheduler.runDue());
        assertThrows(ConflictException.class, () -> service.call(CODE, ann, "power"));
    }

    @Test
    void aFailureToSaveDoesNotStopThePlayersBeingTold() throws Exception {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 9, 1)), List.of(card(2, 1, 3, 3))), true, true);
        when(games.get(CODE)).thenReturn(details(GameStatus.IN_PROGRESS, ann, List.of(human(1, ann), human(2, bob))));
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(repository).finish(any(), any(), anyInt(), any());

        service.call(CODE, ann, "power");

        assertEquals(1, forAnn.ofType("gameOver").size());
        assertEquals(0, registry.size());
    }

    @Test
    void connectingToARunningGameGetsTheLobbyThenTheCurrentView() throws Exception {
        startGame(NO_CHOOSE, List.of(List.of(card(1, 1, 3, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 9, 3), card(2, 2, 4, 4))), true, true);
        FakeSocket fresh = new FakeSocket();
        Connection c = fresh.connect(CODE, bob, clock.instant());
        hub.add(c);
        service.onConnect(c);

        assertEquals(List.of("lobby", "view"), fresh.sent.stream().map(LivePlayTest::parseType).toList());
        JsonNode view = lastView(fresh);
        assertEquals(2, view.get("seat").asInt());
        assertEquals(1, view.get("ownHand").size());
        assertTrue(fresh.sent.stream().noneMatch(s -> s.contains("S1-")), "a reconnecting player learns nothing about seat 1's cards");
        verify(repository, never()).finish(any(), any(), anyInt(), any());
    }

    @Test
    void cpuSeatsPlayOnTheirOwnAfterAHumanMoves() throws Exception {
        GameEngine engine = LiveTestKit.engine(clock, NO_CHOOSE, List.of(com.cards.game.SeatSpec.human(), com.cards.game.SeatSpec.cpu(DifficultyLevel.easy)),
                List.of(List.of(card(1, 1, 3, 1), card(1, 2, 5, 5)), List.of(card(2, 1, 9, 3), card(2, 2, 4, 4))));
        var seats = List.of(human(1, ann), LiveTestKit.cpu(2, DifficultyLevel.easy));
        GameDetails running = details(GameStatus.IN_PROGRESS, ann, seats);
        when(games.get(CODE)).thenReturn(running);
        live = new LiveGame(CODE, running.game().id(), engine, LiveTestKit.DECK, seats);
        registry.put(live);
        forAnn = connect(ann);
        play.begin(live, running);

        service.call(CODE, ann, "power");

        assertTrue(eventTypes(forAnn).contains("roundWon"));
        assertEquals(2, lastView(forAnn).get("callerSeat").asInt());          // the CPU won, so it called and committed on its own
    }
}
