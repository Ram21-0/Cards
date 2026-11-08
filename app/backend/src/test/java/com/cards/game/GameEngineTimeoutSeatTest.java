package com.cards.game;

import com.cards.game.GameEvent.CardChosen;
import com.cards.game.GameEvent.StatCalled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.cards.game.TestKit.DEFAULT;
import static com.cards.game.TestKit.card;
import static com.cards.game.TestKit.hand;
import static com.cards.game.TestKit.hands;
import static com.cards.game.TestKit.humansEngine;
import static com.cards.game.TestKit.only;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GameEngine#timeoutSeat}: what an absent human's turn turns into (live play's disconnect grace). */
class GameEngineTimeoutSeatTest {

    @Test
    void anAbsentCallerGetsARandomStat() {
        GameEngine e = humansEngine(TestKit.NO_CHOOSE, 1, hands(hand(card(11, 5), card(12, 6)), hand(card(21, 7), card(22, 8))));
        List<GameEvent> events = e.timeoutSeat(1);
        e.checkInvariants();
        StatCalled called = only(events, StatCalled.class).get(0);
        assertTrue(called.auto());
        assertEquals(1, called.seat());
        assertTrue(e.phase() != Phase.AWAIT_CALL, "the stat is set and the round moved on (a tie on the random stat starts a clash)");
    }

    @Test
    void anAbsentCallerWhoMayChooseTopCardsThenTheStatIsNotYetCalled() {
        GameEngine e = humansEngine(DEFAULT, 1, hands(hand(card(11, 5), card(12, 6), card(13, 7)), hand(card(21, 7), card(22, 8))));
        assertEquals(Phase.AWAIT_CHOOSE_CALLER, e.phase());

        List<GameEvent> events = e.timeoutSeat(1);

        assertEquals(new CardChosen(1, true), only(events, CardChosen.class).get(0));
        assertEquals(Phase.AWAIT_CALL, e.phase(), "the timeout of one step does not also call the stat");
        assertEquals(11, e.view(1).committed().id(), "the top card");
    }

    @Test
    void anAbsentChooserOnlyLosesTheirOwnChoice() {
        GameEngine e = humansEngine(DEFAULT, 1, hands(hand(card(11, 5)), hand(card(21, 7), card(22, 8)), hand(card(31, 3), card(32, 2))));
        e.call(1, "power");
        assertEquals(Phase.AWAIT_CHOOSE_OTHERS, e.phase());
        assertEquals(List.of(2, 3), List.copyOf(e.actingSeats()));

        List<GameEvent> events = e.timeoutSeat(2);

        assertEquals(new CardChosen(2, true), only(events, CardChosen.class).get(0));
        assertEquals(Phase.AWAIT_CHOOSE_OTHERS, e.phase());
        assertEquals(List.of(3), List.copyOf(e.actingSeats()), "seat 3 may still choose");
    }

    @Test
    void doesNothingForASeatThatIsNotActing() {
        GameEngine e = humansEngine(TestKit.NO_CHOOSE, 1, hands(hand(card(11, 5), card(12, 6)), hand(card(21, 7), card(22, 8))));
        assertTrue(e.timeoutSeat(2).isEmpty());
        assertEquals(Phase.AWAIT_CALL, e.phase());
    }

    @Test
    void aClashRaceIsLeftToItsOwnTimer() {
        GameEngine e = humansEngine(TestKit.NO_CHOOSE, 1, hands(hand(card(11, 5), card(12, 6)), hand(card(21, 5), card(22, 8))));
        e.call(1, "power");
        assertEquals(Phase.CLASH_AWAIT_CALL, e.phase());
        assertTrue(e.timeoutSeat(1).isEmpty());
        assertEquals(Phase.CLASH_AWAIT_CALL, e.phase());
    }
}
