package com.cards.live;

import com.cards.TestClock;
import com.cards.common.Card;
import com.cards.common.DifficultyLevel;
import com.cards.common.GameStatus;
import com.cards.common.Player;
import com.cards.game.GameEngine;
import com.cards.game.GameSettings;
import com.cards.live.LiveTestKit.FakeSocket;
import com.cards.service.service.GameDetails;
import com.cards.service.service.GameService;
import com.cards.service.service.StartedGame;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;

import java.util.ArrayList;
import java.util.List;

import static com.cards.live.LiveTestKit.CODE;
import static com.cards.live.LiveTestKit.FRONTEND;
import static com.cards.live.LiveTestKit.card;
import static com.cards.live.LiveTestKit.cpu;
import static com.cards.live.LiveTestKit.details;
import static com.cards.live.LiveTestKit.human;
import static com.cards.live.LiveTestKit.humans;
import static com.cards.live.LiveTestKit.json;
import static com.cards.live.LiveTestKit.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The glue between lobby actions, the running game and the open sockets. */
class LiveGameServiceTest {

    private final TestClock clock = TestClock.atNow();
    private final GameService games = mock(GameService.class);
    private final GameHub hub = new GameHub(clock, 90);
    private final GameRegistry registry = new GameRegistry();
    private final LiveMessages messages = new LiveMessages(json(), clock, FRONTEND);
    private final LiveGameService live = new LiveGameService(games, hub, registry, messages);
    private final Player ann = player("ann"), bob = player("bob");

    private FakeSocket connect(Player p) {
        FakeSocket socket = new FakeSocket();
        hub.add(socket.connect(CODE, p, clock.instant()));
        return socket;
    }

    private JsonNode parse(String text) throws Exception {
        return json().readTree(text);
    }

    @Test
    void joiningTellsEveryoneInTheLobbyAboutTheNewSeats() throws Exception {
        FakeSocket forAnn = connect(ann), forBob = connect(bob);
        GameDetails two = details(GameStatus.WAITING, ann, List.of(human(1, ann), human(2, bob)));
        when(games.join(CODE, bob)).thenReturn(two);
        when(games.get(CODE)).thenReturn(two);

        assertEquals(two, live.join(CODE, bob));

        JsonNode toAnn = parse(forAnn.last()), toBob = parse(forBob.last());
        assertEquals("lobby", toAnn.get("type").asText());
        assertEquals(2, toAnn.get("game").get("seats").size());
        assertEquals(1, toAnn.get("mySeat").asInt());
        assertEquals(2, toBob.get("mySeat").asInt(), "each connection is told its own seat");
    }

    @Test
    void addingAndRemovingACpuIsPushedToo() throws Exception {
        FakeSocket forAnn = connect(ann);
        GameDetails withCpu = details(GameStatus.WAITING, ann, List.of(human(1, ann), cpu(2, DifficultyLevel.easy)));
        when(games.addCpu(CODE, ann, "easy")).thenReturn(withCpu);
        when(games.get(CODE)).thenReturn(withCpu);
        live.addCpu(CODE, ann, "easy");
        assertEquals("easy", parse(forAnn.last()).get("game").get("seats").get(1).get("difficulty").asText());

        GameDetails without = details(GameStatus.WAITING, ann, List.of(human(1, ann)));
        when(games.removeSeat(CODE, ann, 2)).thenReturn(without);
        when(games.get(CODE)).thenReturn(without);
        live.removeSeat(CODE, ann, 2);
        assertEquals(1, parse(forAnn.last()).get("game").get("seats").size());
        assertEquals(2, forAnn.ofType("lobby").size());
    }

    @Test
    void aPushAlwaysShowsTheLatestStateNotTheStateOfTheCallThatTriggeredIt() throws Exception {
        FakeSocket forAnn = connect(ann);
        GameDetails stale = details(GameStatus.WAITING, ann, List.of(human(1, ann)));
        GameDetails latest = details(GameStatus.WAITING, ann, List.of(human(1, ann), human(2, bob), cpu(3, DifficultyLevel.hard)));
        when(games.join(CODE, bob)).thenReturn(stale);                           // what that request saw
        when(games.get(CODE)).thenReturn(latest);                                 // what the database says by push time
        live.join(CODE, bob);
        assertEquals(3, parse(forAnn.last()).get("game").get("seats").size());
    }

    @Test
    void leavingClosesTheLeaversSocketsAndUpdatesTheOthers() throws Exception {
        FakeSocket forAnn = connect(ann), forBob = connect(bob);
        GameDetails afterLeave = details(GameStatus.WAITING, ann, List.of(human(1, ann)));
        when(games.leave(CODE, bob)).thenReturn(afterLeave);
        when(games.get(CODE)).thenReturn(afterLeave);

        live.leave(CODE, bob);

        assertEquals(CloseStatus.NORMAL, forBob.closedWith);
        assertEquals(1, parse(forAnn.last()).get("game").get("seats").size());
        assertEquals(null, forAnn.closedWith);
    }

    private StartedGame startedGame() {
        List<List<Card>> hands = new ArrayList<>();
        for (int seat = 1; seat <= 2; seat++) {
            List<Card> hand = new ArrayList<>();
            for (int k = 0; k < 5; k++) hand.add(card(seat, k, k + 1, 5));
            hands.add(hand);
        }
        GameEngine engine = LiveTestKit.engine(clock, new GameSettings(500, 3, 5, null), humans(2), hands);
        return new StartedGame(details(GameStatus.IN_PROGRESS, ann, List.of(human(1, ann), human(2, bob))), engine);
    }

    @Test
    void startingRegistersTheRunningGameAndSendsStartedThenEachPlayersOwnView() throws Exception {
        FakeSocket forAnn = connect(ann), forBob = connect(bob);
        when(games.start(CODE, ann)).thenReturn(startedGame());

        live.start(CODE, ann);

        assertTrue(registry.find(CODE).isPresent());
        for (FakeSocket socket : List.of(forAnn, forBob)) {
            assertEquals(List.of("started", "view"), socket.sent.stream().map(m -> {
                try { return parse(m).get("type").asText(); } catch (Exception e) { throw new IllegalStateException(e); }
            }).toList());
        }
        assertEquals(1, parse(forAnn.last()).get("seat").asInt());
        assertEquals(2, parse(forBob.last()).get("seat").asInt());
        assertFalse(forAnn.last().contains("S2-"), "ann's view contains none of bob's cards");
        assertFalse(forBob.last().contains("S1-"), "bob's view contains none of ann's cards");
    }

    @Test
    void aSocketThatOpensLaterGetsTheLobbyAndTheCurrentViewAsAReconnect() throws Exception {
        when(games.start(CODE, ann)).thenReturn(startedGame());
        live.start(CODE, ann);
        when(games.get(CODE)).thenReturn(details(GameStatus.IN_PROGRESS, ann, List.of(human(1, ann), human(2, bob))));

        FakeSocket reconnecting = new FakeSocket();
        Connection c = reconnecting.connect(CODE, bob, clock.instant());
        hub.add(c);
        live.onConnect(c);

        assertEquals("lobby", parse(reconnecting.sent.get(0)).get("type").asText());
        JsonNode view = parse(reconnecting.sent.get(1));
        assertEquals("view", view.get("type").asText());
        assertEquals(2, view.get("seat").asInt());
        assertEquals("AWAIT_CALL", view.get("phase").asText());
    }

    @Test
    void aSocketOpeningBeforeTheGameStartsOnlyGetsTheLobby() throws Exception {
        when(games.get(CODE)).thenReturn(details(GameStatus.WAITING, ann, List.of(human(1, ann))));
        FakeSocket socket = new FakeSocket();
        Connection c = socket.connect(CODE, ann, clock.instant());
        hub.add(c);
        live.onConnect(c);
        assertEquals(1, socket.sent.size());
        assertEquals("lobby", parse(socket.sent.get(0)).get("type").asText());
    }

    @Test
    void whenNobodyIsConnectedAPushDoesNotEvenReadTheGame() {
        live.pushLobby(CODE);
        org.mockito.Mockito.verifyNoInteractions(games);
    }
}
