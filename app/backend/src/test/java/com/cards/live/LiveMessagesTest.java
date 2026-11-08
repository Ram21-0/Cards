package com.cards.live;

import com.cards.TestClock;
import com.cards.common.Card;
import com.cards.common.DifficultyLevel;
import com.cards.common.GameStatus;
import com.cards.common.Player;
import com.cards.game.GameEngine;
import com.cards.game.GameSettings;
import com.cards.service.service.GameDetails;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

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

class LiveMessagesTest {

    private final TestClock clock = TestClock.atNow();
    private final LiveMessages messages = new LiveMessages(json(), clock, FRONTEND);
    private final Player ann = player("ann"), bob = player("bob");

    private JsonNode parse(String text) throws Exception {
        return json().readTree(text);
    }

    // ---------------------------------------------------------------- lobby / started

    @Test
    void theLobbyMessageCarriesTheGameTheViewersSeatAndWhoIsHost() throws Exception {
        GameDetails d = details(GameStatus.WAITING, ann, List.of(human(1, ann), cpu(2, DifficultyLevel.hard), human(3, bob)));

        JsonNode forBob = parse(messages.lobby(d, bob.id()));
        assertEquals("lobby", forBob.get("type").asText());
        assertEquals(3, forBob.get("mySeat").asInt());
        assertEquals(CODE, forBob.get("game").get("joinCode").asText());
        assertEquals(FRONTEND + "/?join=" + CODE, forBob.get("game").get("joinUrl").asText());
        assertEquals("WAITING", forBob.get("game").get("status").asText());
        assertEquals(1, forBob.get("game").get("hostSeat").asInt());
        assertEquals(3, forBob.get("game").get("seats").size());
        assertEquals("CPU", forBob.get("game").get("seats").get(1).get("kind").asText());
        assertEquals("hard", forBob.get("game").get("seats").get(1).get("difficulty").asText());
        assertTrue(forBob.has("serverTime"));

        assertEquals(1, parse(messages.lobby(d, ann.id())).get("mySeat").asInt());
        assertTrue(parse(messages.lobby(d, player("stranger").id())).get("mySeat").isNull());
    }

    @Test
    void messagesNeverContainPlayerIds() {
        GameDetails d = details(GameStatus.WAITING, ann, List.of(human(1, ann), human(2, bob)));
        for (String text : List.of(messages.lobby(d, ann.id()), messages.started(d, bob.id()))) {
            assertFalse(text.contains(ann.id().toString()), "no account ids in a message: " + text);
            assertFalse(text.contains(bob.id().toString()), text);
        }
    }

    @Test
    void theStartedMessageIsTheGameBodyWithTheNewStatus() throws Exception {
        GameDetails d = details(GameStatus.IN_PROGRESS, ann, List.of(human(1, ann), human(2, bob)));
        JsonNode m = parse(messages.started(d, ann.id()));
        assertEquals("started", m.get("type").asText());
        assertEquals("IN_PROGRESS", m.get("game").get("status").asText());
        assertEquals(1, m.get("mySeat").asInt());
    }

    @Test
    void aFinishedGameCarriesPlacementsOnItsSeats() throws Exception {
        GameDetails d = new GameDetails(LiveTestKit.game(GameStatus.FINISHED, ann.id()), LiveTestKit.DECK, List.of(
                new com.cards.common.SeatInfo(1, com.cards.common.SeatKind.HUMAN, "ann", null, ann.id(), 1),
                new com.cards.common.SeatInfo(2, com.cards.common.SeatKind.HUMAN, "bob", null, bob.id(), 2)));
        JsonNode seats = parse(messages.lobby(d, ann.id())).get("game").get("seats");
        assertEquals(1, seats.get(0).get("placement").asInt());
        assertEquals(2, seats.get(1).get("placement").asInt());
    }

    @Test
    void pongAndErrorMessages() throws Exception {
        assertEquals("pong", parse(messages.pong()).get("type").asText());
        JsonNode e = parse(messages.error("nope"));
        assertEquals("error", e.get("type").asText());
        assertEquals("nope", e.get("error").asText());
    }

    // ---------------------------------------------------------------- hidden information

    /** Every card in this JSON (an object with an "id" and a "name"): the ids and names found. */
    private static void collectCards(JsonNode node, List<Integer> ids, List<String> names) {
        if (node.isObject() && node.has("id") && node.has("name") && node.has("stats")) {
            ids.add(node.get("id").asInt());
            names.add(node.get("name").asText());
        }
        node.forEach(child -> collectCards(child, ids, names));
    }

    @Test
    void aViewHoldsOnlyTheSeatsOwnCardsAndTheOthersAreJustCounts() throws Exception {
        List<List<Card>> hands = new ArrayList<>();
        for (int seat = 1; seat <= 3; seat++) {
            List<Card> hand = new ArrayList<>();
            for (int k = 0; k < 5; k++) hand.add(card(seat, k, k + 1, 5));
            hands.add(hand);
        }
        GameEngine engine = LiveTestKit.engine(clock, new GameSettings(500, 3, 5, null), humans(3), hands);
        LiveGame live = new LiveGame(CODE, LiveTestKit.game(GameStatus.IN_PROGRESS, ann.id()).id(), engine, LiveTestKit.DECK,
                List.of(human(1, ann), human(2, bob), human(3, player("cy"))));

        for (int seat = 1; seat <= 3; seat++) {
            JsonNode view = parse(messages.view(live, seat));
            assertEquals("view", view.get("type").asText());
            assertEquals(seat, view.get("seat").asInt());
            List<Integer> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            collectCards(view, ids, names);
            assertFalse(ids.isEmpty(), "seat " + seat + " sees its own cards");
            for (int id : ids) assertEquals(seat, id / 1000, "seat " + seat + " was shown card " + id + " which belongs to seat " + id / 1000);
            for (String name : names) assertTrue(name.startsWith("S" + seat + "-"), name);
            // everybody's card count is public
            assertEquals(5, view.get("handSizes").get("1").asInt() + 0 * 1);
            assertEquals(5, view.get("handSizes").get("2").asInt());
            assertEquals(5, view.get("handSizes").get("3").asInt());
        }
    }

    @Test
    void theViewOfTheCallerShowsTheCommittedCardWithDisplayValuesAndNobodyElsesDoes() throws Exception {
        List<List<Card>> hands = List.of(
                List.of(card(1, 0, 9, 5), card(1, 1, 1, 5), card(1, 2, 1, 5), card(1, 3, 1, 5), card(1, 4, 1, 5)),
                List.of(card(2, 0, 2, 5), card(2, 1, 1, 5), card(2, 2, 1, 5), card(2, 3, 1, 5), card(2, 4, 1, 5)));
        GameEngine engine = LiveTestKit.engine(clock, new GameSettings(500, 3, 5, null), humans(2), hands);
        LiveGame live = new LiveGame(CODE, LiveTestKit.game(GameStatus.IN_PROGRESS, ann.id()).id(), engine, LiveTestKit.DECK,
                List.of(human(1, ann), human(2, bob)));

        JsonNode caller = parse(messages.view(live, 1));
        assertEquals("AWAIT_CALL", caller.get("phase").asText());
        assertEquals(1, caller.get("callerSeat").asInt());
        assertTrue(caller.get("canCall").asBoolean());
        assertEquals(1000, caller.get("committed").get("id").asInt(), "the caller's top card is in play");
        assertEquals("9", caller.get("committed").get("stats").get("power").get("displayValue").asText());
        assertEquals("5s", caller.get("committed").get("stats").get("speed").get("displayValue").asText(), "display values come from the deck");

        JsonNode other = parse(messages.view(live, 2));
        assertTrue(other.get("committed").isNull(), "seat 2 has not committed anything, and cannot see seat 1's card");
        assertFalse(other.get("canCall").asBoolean());
        assertFalse(messages.view(live, 2).contains("S1-0"));
    }
}
