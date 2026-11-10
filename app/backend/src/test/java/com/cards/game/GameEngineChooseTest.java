package com.cards.game;

import com.cards.TestClock;
import com.cards.common.Card;
import com.cards.game.GameEvent.CardChosen;
import com.cards.game.GameEvent.ClashStarted;
import com.cards.game.GameEvent.Revealed;
import com.cards.game.GameEvent.RoundWon;
import com.cards.game.GameEvent.StatCalled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static com.cards.game.TestKit.DEFAULT;
import static com.cards.game.TestKit.card;
import static com.cards.game.TestKit.hand;
import static com.cards.game.TestKit.hands;
import static com.cards.game.TestKit.humans;
import static com.cards.game.TestKit.humansEngine;
import static com.cards.game.TestKit.ids;
import static com.cards.game.TestKit.only;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RULES.md §4 CHOOSE (threshold 3 by default) and §8 hidden information. */
class GameEngineChooseTest {

    private static List<Card> pile(int seat, int topPower, int count) {
        List<Card> cards = new ArrayList<>();
        for (int k = 0; k < count; k++) cards.add(card(seat * 100 + k, k == 0 ? topPower : 1));
        return cards;
    }

    // ---------------------------------------------------------------- who is offered CHOOSE

    @ParameterizedTest(name = "caller with {0} cards: offered = {1}")
    @CsvSource({"3,true", "2,true", "1,false", "4,false", "6,false"})
    void theCallerIsOfferedChooseOnlyWithTwoUpToThreeCards(int cards, boolean offered) {
        GameEngine e = humansEngine(DEFAULT, 1, hands(pile(1, 5, cards), pile(2, 5, 6)));
        assertEquals(offered ? Phase.AWAIT_CHOOSE_CALLER : Phase.AWAIT_CALL, e.phase());
        PlayerView view = e.view(1);
        assertEquals(offered, view.canChoose());
        assertEquals(!offered, view.canCall());
        if (offered) {
            assertEquals(cards, view.ownHand().size(), "the whole hand while CHOOSE applies");
            assertNull(view.committed(), "nothing is in play until they choose");
        } else {
            assertTrue(view.committed() != null, "the caller's top card is already in play");
            assertEquals(Math.min(1, cards - 1), view.ownHand().size(), "and only the next top card is visible");
        }
    }

    @ParameterizedTest(name = "other player with {0} cards: offered = {1}")
    @CsvSource({"3,true", "2,true", "1,false", "4,false"})
    void otherPlayersAreOfferedChooseOnceTheStatIsKnown(int cards, boolean offered) {
        GameEngine e = humansEngine(DEFAULT, 1, hands(pile(1, 5, 6), pile(2, 7, cards), pile(3, 1, 6)));
        e.call(1, "power");
        e.checkInvariants();
        if (offered) {
            assertEquals(Phase.AWAIT_CHOOSE_OTHERS, e.phase());
            assertEquals(List.of(2), List.copyOf(e.actingSeats()));
            assertTrue(e.view(2).canChoose());
            assertEquals(cards, e.view(2).ownHand().size());
            assertFalse(e.view(1).canChoose());
        } else {
            assertEquals(2, e.round(), "no choice to wait for: the round was resolved straight away");
            assertEquals(2, e.callerSeat(), "seat 2's 7 won");
            assertNotEquals(Phase.AWAIT_CHOOSE_OTHERS, e.phase());
        }
    }

    @Test
    void chooseCanBeSwitchedOff() {
        for (int threshold : new int[]{0, 1}) {
            GameEngine e = humansEngine(new GameSettings(500, threshold, 5, null), 1, hands(pile(1, 5, 2), pile(2, 5, 2)));
            assertEquals(Phase.AWAIT_CALL, e.phase(), "threshold " + threshold);
        }
    }

    @Test
    void aHigherThresholdOffersChooseToBiggerHands() {
        GameEngine e = humansEngine(new GameSettings(500, 5, 5, null), 1, hands(pile(1, 5, 5), pile(2, 5, 6)));
        assertEquals(Phase.AWAIT_CHOOSE_CALLER, e.phase());
        assertEquals(5, e.view(1).ownHand().size());
    }

    // ---------------------------------------------------------------- choosing

    @Test
    void theCallerChoosesACardBeforeCallingTheStat() {
        GameEngine e = humansEngine(DEFAULT, 1, hands(hand(card(11, 1), card(12, 9), card(13, 3)), pile(2, 5, 6)));
        assertEquals(Phase.AWAIT_CHOOSE_CALLER, e.phase());
        assertThrows(GameRuleException.class, () -> e.call(1, "power"), "choose first, then call");

        List<GameEvent> chosen = e.choose(1, 12);
        e.checkInvariants();
        assertEquals(new CardChosen(1, false), only(chosen, CardChosen.class).get(0));
        assertEquals(Phase.AWAIT_CALL, e.phase());
        assertEquals(List.of(11, 13), ids(e.handOf(1)), "the chosen card leaves the hand, the others keep their order");
        assertEquals(12, e.view(1).committed().id());
        assertNull(e.view(2).committed(), "nobody sees another seat's committed card");

        List<GameEvent> events = e.call(1, "power");
        e.checkInvariants();
        assertEquals(12, only(events, Revealed.class).get(0).cards().get(1).id(), "the chosen card is the one revealed");
        assertEquals(new RoundWon(1, 2), only(events, RoundWon.class).get(0));
        // seat 1 won 2 cards (its own 12, then seat 2's 200) and, calling next, put its top card 11 in play
        assertEquals(List.of(13, 12, 200), ids(e.handOf(1)));
        assertEquals(11, e.view(1).committed().id());
    }

    @Test
    void everyoneElseChoosesAfterTheStatIsCalledAndTheChosenCardLeavesTheirHand() {
        GameEngine e = humansEngine(DEFAULT, 1, hands(pile(1, 5, 6), hand(card(21, 1), card(22, 9), card(23, 3))));
        e.call(1, "power");
        assertEquals(Phase.AWAIT_CHOOSE_OTHERS, e.phase());
        assertEquals(List.of(21, 22, 23), ids(e.view(2).ownHand()), "the whole hand, in order");

        List<GameEvent> events = e.choose(2, 22);
        e.checkInvariants();
        assertEquals(new CardChosen(2, false), only(events, CardChosen.class).get(0));
        assertEquals(22, only(events, Revealed.class).get(0).cards().get(2).id());
        assertEquals(new RoundWon(2, 2), only(events, RoundWon.class).get(0));
        // after the round seat 2 (the new caller, 4 cards: no CHOOSE) has put its top card 21 in play
        assertEquals(List.of(23, 22, 100), ids(e.handOf(2)));
        assertEquals(21, e.view(2).committed().id());
    }

    @Test
    void notChoosingMeansTheTopCardAfterTheTurnTimeout() {
        TestClock clock = TestClock.atNow();
        GameEngine e = TestKit.engine(new GameSettings(500, 3, 5, 10), humans(2), 1, clock, new SplittableRandom(1),
                hands(pile(1, 5, 6), hand(card(21, 1), card(22, 9), card(23, 3))));
        e.call(1, "power");
        assertEquals(Phase.AWAIT_CHOOSE_OTHERS, e.phase());
        clock.advance(Duration.ofSeconds(9));
        assertTrue(e.tick(clock.instant()).isEmpty());
        clock.advance(Duration.ofSeconds(1));
        List<GameEvent> events = e.tick(clock.instant());
        e.checkInvariants();
        assertEquals(new CardChosen(2, true), only(events, CardChosen.class).get(0));
        assertEquals(21, only(events, Revealed.class).get(0).cards().get(2).id(), "the top card, power 1");
        assertEquals(1, only(events, RoundWon.class).get(0).seat(), "seat 1's 5 beats the 1");
    }

    @Test
    void aCallerWhoNeitherChoosesNorCallsGetsTheTopCardAndARandomStat() {
        TestClock clock = TestClock.atNow();
        GameEngine e = TestKit.engine(new GameSettings(500, 3, 5, 10), humans(2), 1, clock, new SplittableRandom(1),
                hands(hand(card(11, 1), card(12, 9), card(13, 3)), pile(2, 5, 6)));
        clock.advance(Duration.ofSeconds(10));
        List<GameEvent> first = e.tick(clock.instant());
        assertEquals(new CardChosen(1, true), only(first, CardChosen.class).get(0));
        assertEquals(Phase.AWAIT_CALL, e.phase());
        assertEquals(11, e.view(1).committed().id(), "top card");
        clock.advance(Duration.ofSeconds(10));
        StatCalled called = only(e.tick(clock.instant()), StatCalled.class).get(0);
        assertEquals(1, called.seat());
        assertTrue(called.auto());
        e.checkInvariants();
    }

    @Test
    void chooseIsRefusedWhenItDoesNotApply() {
        GameEngine e = humansEngine(DEFAULT, 1, hands(hand(card(11, 1), card(12, 9), card(13, 3)), pile(2, 5, 6)));
        assertThrows(GameRuleException.class, () -> e.choose(1, 999), "a card that is not in the hand");
        assertThrows(GameRuleException.class, () -> e.choose(2, 200), "seat 2 is not offered CHOOSE");
        assertThrows(GameRuleException.class, () -> e.choose(7, 11), "no such seat");
        e.choose(1, 11);
        assertThrows(GameRuleException.class, () -> e.choose(1, 12), "already chose");
        e.call(1, "power");
        assertThrows(GameRuleException.class, () -> e.choose(1, 12), "the round moved on");
    }

    @Test
    void choosingTheTopCardIsHowYouDeclineTheOffer() {
        GameEngine e = humansEngine(DEFAULT, 1, hands(hand(card(11, 1), card(12, 9)), pile(2, 5, 6)));
        e.choose(1, 11);
        assertEquals(List.of(12), ids(e.handOf(1)));
    }

    // ---------------------------------------------------------------- choosing inside a clash

    @Test
    void inAClashTheTiedPlayersMayChooseOnceTheStatIsCalled() {
        GameEngine e = humansEngine(DEFAULT, 1, hands(
                hand(card(11, 5), card(12, 9), card(13, 1), card(14, 2)),       // 4 cards: no CHOOSE in round 1
                hand(card(21, 5), card(22, 1), card(23, 1), card(24, 1))));
        assertEquals(Phase.AWAIT_CALL, e.phase());
        List<GameEvent> clash = e.call(1, "power");                             // 5 vs 5: tie, pot [11, 21]
        assertEquals(new ClashStarted(List.of(1, 2), 1, 2), only(clash, ClashStarted.class).get(0));

        List<GameEvent> afterStat = e.call(2, "power");
        e.checkInvariants();
        assertEquals(Phase.CLASH_AWAIT_CHOOSE, e.phase(), "3 cards each now: both may CHOOSE");
        assertEquals(List.of(1, 2), List.copyOf(e.actingSeats()));
        assertEquals(List.of(12, 13, 14), ids(e.view(1).ownHand()));
        assertEquals(new StatCalled(2, "power", false), only(afterStat, StatCalled.class).get(0));

        e.choose(1, 12);                                                        // seat 1's 9
        List<GameEvent> done = e.choose(2, 22);                                 // seat 2's top card
        e.checkInvariants();
        assertEquals(new RoundWon(1, 4), only(done, RoundWon.class).get(0), "own 12, seat 2's 22, the pot 11 and 21");
        // after winning seat 1 holds 13, 14 and then the 4 won cards (it has 6 cards: no CHOOSE), and has put 13 in play
        assertEquals(List.of(14, 12, 22, 11, 21), ids(e.handOf(1)));
        assertEquals(13, e.view(1).committed().id());
    }

    // ---------------------------------------------------------------- hidden information (§8)

    @Test
    void aViewNeverContainsAnotherSeatsCards() {
        GameEngine e = humansEngine(DEFAULT, 1, hands(pile(1, 5, 6), pile(2, 5, 6), pile(3, 5, 6)));
        for (int seat = 1; seat <= 3; seat++) {
            PlayerView view = e.view(seat);
            List<Integer> mine = ids(e.handOf(seat));
            mine = new ArrayList<>(mine);
            if (view.committed() != null) mine.add(view.committed().id());
            for (Card c : view.ownHand()) assertTrue(mine.contains(c.id()), "seat " + seat + " sees only its own card");
        }
        PlayerView v = e.view(2);
        assertEquals(1, v.ownHand().size(), "just the top card");
        assertEquals(6, v.handSizes().get(1));
        assertEquals(6, v.handSizes().get(3));
        assertEquals(Phase.AWAIT_CALL, v.phase());
        assertEquals(1, v.callerSeat());
    }

    @Test
    void cardCountsIncludeTheCardAPlayerHasAlreadyPutInPlay() {
        GameEngine e = humansEngine(DEFAULT, 1, hands(pile(1, 5, 6), pile(2, 5, 6)));
        assertEquals(6, e.handSize(1), "the caller's top card is in play but still theirs");
        assertEquals(6, e.view(2).handSizes().get(1));
    }
}
