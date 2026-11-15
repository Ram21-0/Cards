package com.cards.game;

import com.cards.TestClock;
import com.cards.common.DifficultyLevel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;

import static com.cards.game.TestKit.card;
import static com.cards.game.TestKit.hand;
import static com.cards.game.TestKit.hands;
import static com.cards.game.TestKit.humans;
import static com.cards.game.TestKit.only;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ROUND_RESULT hold: the result stays up, nobody acts, and the next round's clocks start only afterwards. */
class GameEngineResultHoldTest {

    private final TestClock clock = TestClock.atNow();
    /** CHOOSE off, a 10 s turn timer, a 6 s hold (9 s after a clash or an elimination). */
    private final GameSettings held = new GameSettings(500, 0, 5, 10, 6);

    private GameEngine engine(GameSettings settings, List<SeatSpec> specs, List<List<com.cards.common.Card>> hands) {
        return TestKit.engine(settings, specs, 1, clock, new SplittableRandom(1), hands);
    }

    @Test
    void aNormalRoundIsFollowedByASixSecondHold() {
        Instant t0 = clock.instant();
        GameEngine e = engine(held, humans(2), hands(hand(card(11, 9), card(12, 1)), hand(card(21, 2), card(22, 3))));

        List<GameEvent> events = e.call(1, "power");

        assertEquals(Phase.ROUND_RESULT, e.phase());
        assertEquals(1, e.round(), "the next round has not started");
        assertEquals(t0.plusSeconds(6), e.view(1).deadline());
        assertEquals(t0.plusSeconds(6), e.nextWakeup().orElseThrow());
        assertEquals(1, only(events, GameEvent.RoundWon.class).size());
        assertTrue(only(events, GameEvent.RoundStarted.class).isEmpty(), "no RoundStarted yet");
        assertTrue(e.actingSeats().isEmpty());
        assertFalse(e.view(1).canCall() || e.view(1).canChoose() || e.view(2).canCall());
        e.checkInvariants();
    }

    @Test
    void callsAndChoosesAreRefusedDuringTheHold() {
        GameEngine e = engine(held, humans(2), hands(hand(card(11, 9), card(12, 1)), hand(card(21, 2), card(22, 3))));
        e.call(1, "power");

        for (int seat : new int[]{1, 2}) {
            GameRuleException call = assertThrows(GameRuleException.class, () -> e.call(seat, "power"));
            assertEquals(GameRuleException.Kind.WRONG_PHASE, call.kind());
            assertTrue(call.getMessage().contains("round result"), call.getMessage());
            GameRuleException choose = assertThrows(GameRuleException.class, () -> e.choose(seat, 12));
            assertEquals(GameRuleException.Kind.WRONG_PHASE, choose.kind());
        }
        assertEquals(Phase.ROUND_RESULT, e.phase());
    }

    @Test
    void theNextRoundAndItsTurnTimerStartOnlyWhenTheHoldIsOver() {
        Instant t0 = clock.instant();
        GameEngine e = engine(held, humans(2), hands(hand(card(11, 9), card(12, 1)), hand(card(21, 2), card(22, 3))));
        e.call(1, "power");

        clock.advance(Duration.ofSeconds(5));
        assertTrue(e.tick(clock.instant()).isEmpty());
        assertEquals(Phase.ROUND_RESULT, e.phase());

        clock.advance(Duration.ofSeconds(2));                           // 7 s: the hold ended at 6 s
        List<GameEvent> events = e.tick(clock.instant());

        assertEquals(2, e.round());
        assertEquals(Phase.AWAIT_CALL, e.phase());
        assertEquals(1, e.callerSeat(), "the winner calls next");
        assertEquals(t0.plusSeconds(16), e.view(1).deadline(), "the 10 s turn timer started when the hold ended (6 s), not when the round did");
        assertEquals(2, only(events, GameEvent.RoundStarted.class).get(0).round());
    }

    @Test
    void aTurnTimerCannotRunOutInsideTheHold() {
        GameEngine e = engine(held, humans(2), hands(hand(card(11, 9), card(12, 1)), hand(card(21, 2), card(22, 3))));
        e.call(1, "power");
        clock.advance(Duration.ofSeconds(6));                           // exactly the hold: the round starts, nobody auto-played yet
        List<GameEvent> events = e.tick(clock.instant());
        assertTrue(only(events, GameEvent.StatCalled.class).isEmpty());
        assertEquals(Phase.AWAIT_CALL, e.phase());
    }

    @Test
    void aClashRoundHoldsThreeSecondsLonger() {
        Instant t0 = clock.instant();
        GameEngine e = engine(held, humans(3), hands(
                hand(card(11, 5), card(12, 8)), hand(card(21, 5), card(22, 3), card(23, 4)), hand(card(31, 1), card(32, 1), card(33, 1))));
        e.call(1, "power");
        assertEquals(Phase.CLASH_AWAIT_CALL, e.phase());

        e.call(2, "power");                                             // seat 1's 8 beats seat 2's 3

        assertEquals(Phase.ROUND_RESULT, e.phase());
        assertEquals(t0.plusSeconds(9), e.view(1).deadline(), "6 + 3");
        assertEquals(1, e.round());
    }

    @Test
    void aRoundWithAnEliminationHoldsThreeSecondsLonger() {
        Instant t0 = clock.instant();
        GameEngine e = engine(held, humans(3), hands(hand(card(11, 9), card(12, 9)), hand(card(21, 1)), hand(card(31, 2), card(32, 2))));

        List<GameEvent> events = e.call(1, "power");

        assertEquals(1, only(events, GameEvent.PlayerEliminated.class).size());
        assertEquals(Phase.ROUND_RESULT, e.phase());
        assertEquals(t0.plusSeconds(9), e.view(1).deadline());
    }

    @Test
    void cpuSeatsWaitUnderTheResultAndActOnlyAfterIt() {
        // seat 1 (human) calls and loses to the CPU in seat 2, which will call next
        GameEngine e = engine(new GameSettings(500, 0, 5, null, 6), List.of(SeatSpec.human(), SeatSpec.cpu(DifficultyLevel.hard), SeatSpec.human()),
                hands(hand(card(11, 1), card(12, 1)), hand(card(21, 9), card(22, 9)), hand(card(31, 2), card(32, 2))));
        List<GameEvent> events = e.call(1, "power");
        assertEquals(Phase.ROUND_RESULT, e.phase());
        assertTrue(only(events, GameEvent.StatCalled.class).size() == 1, "only the human's call so far");

        clock.advance(Duration.ofSeconds(5));
        assertTrue(e.tick(clock.instant()).isEmpty(), "the CPU does nothing under the result");

        clock.advance(Duration.ofSeconds(1));
        List<GameEvent> after = e.tick(clock.instant());
        assertEquals(2, e.callerSeat());
        assertTrue(only(after, GameEvent.StatCalled.class).stream().anyMatch(c -> c.seat() == 2), "the CPU calls as soon as the next round starts: " + after);
        assertNotEquals(Phase.ROUND_RESULT, e.phase());
    }

    @Test
    void aHoldOfZeroBehavesLikeBefore() {
        GameEngine e = engine(new GameSettings(500, 0, 5, 10, 0), humans(2), hands(hand(card(11, 9), card(12, 1)), hand(card(21, 2), card(22, 3))));
        List<GameEvent> events = e.call(1, "power");
        assertEquals(2, e.round());
        assertEquals(Phase.AWAIT_CALL, e.phase());
        assertEquals(1, only(events, GameEvent.RoundStarted.class).size(), "RoundStarted comes with the result");
    }

    @Test
    void theRoundThatEndsTheGameHasNoHold() {
        GameEngine e = engine(held, humans(2), hands(hand(card(11, 9)), hand(card(21, 1))));
        List<GameEvent> events = e.call(1, "power");
        assertTrue(e.isOver());
        assertEquals(Phase.GAME_OVER, e.phase());
        assertEquals(1, only(events, GameEvent.GameOver.class).size());
    }

    @Test
    void theRoundCapEndsTheGameWithoutAHold() {
        GameSettings capped = new GameSettings(20, 0, 5, null, 6);
        GameEngine e = TestKit.engine(capped, humans(2), 1, clock, new SplittableRandom(1),
                hands(hand(card(11, 9), card(12, 9), card(13, 9)), hand(card(21, 1), card(22, 1), card(23, 1))));
        // winner always the same seat; cards cycle at the bottom, so the game runs into the cap
        int guard = 0;
        while (!e.isOver() && guard++ < 100) {
            if (e.phase() == Phase.ROUND_RESULT) {
                clock.advance(Duration.ofSeconds(10));
                e.tick(clock.instant());
            } else {
                e.call(e.callerSeat(), "power");
            }
            e.checkInvariants();
        }
        assertTrue(e.isOver());
        assertNotEquals(Phase.ROUND_RESULT, e.phase());
    }

    // ---------------------------------------------------------------- the host changes the hold during the game

    private GameEngine inHold() {
        GameEngine e = engine(held, humans(2), hands(hand(card(11, 9), card(12, 1)), hand(card(21, 2), card(22, 3))));
        e.call(1, "power");                                             // a normal round: 6 s hold, started now
        assertEquals(Phase.ROUND_RESULT, e.phase());
        return e;
    }

    @Test
    void loweringTheHoldShortensARunningHoldFromItsStart() {
        Instant t0 = clock.instant();
        GameEngine e = inHold();
        clock.advance(Duration.ofSeconds(2));

        e.setResultHoldSeconds(3);

        assertEquals(Phase.ROUND_RESULT, e.phase());
        assertEquals(t0.plusSeconds(3), e.view(1).deadline(), "start of the hold + the new value");
        clock.advance(Duration.ofSeconds(1));
        e.tick(clock.instant());
        assertEquals(2, e.round(), "ended on time at 3 s");
    }

    @Test
    void loweringBelowTheTimeAlreadyShownEndsTheHoldNowAndTheNextTimerStartsNow() {
        GameEngine e = inHold();
        clock.advance(Duration.ofSeconds(5));
        Instant now = clock.instant();

        List<GameEvent> events = e.setResultHoldSeconds(2);             // 2 s would have ended 3 s ago

        assertEquals(2, e.round(), "the next round started at once");
        assertEquals(1, only(events, GameEvent.RoundStarted.class).size());
        assertEquals(now.plusSeconds(10), e.view(1).deadline(), "the turn timer starts now, not in the past");
    }

    @Test
    void zeroEndsTheRunningHoldNowAndTurnsHoldsOffForGood() {
        GameEngine e = inHold();
        List<GameEvent> events = e.setResultHoldSeconds(0);
        assertEquals(2, e.round());
        assertEquals(1, only(events, GameEvent.RoundStarted.class).size());
        assertEquals(Phase.AWAIT_CALL, e.phase());
        e.call(1, "power");                                             // seat 1 has 3 cards, wins again
        assertNotEquals(Phase.ROUND_RESULT, e.phase(), "no hold any more");
    }

    @Test
    void raisingExtendsARunningHold() {
        Instant t0 = clock.instant();
        GameEngine e = inHold();
        clock.advance(Duration.ofSeconds(5));

        e.setResultHoldSeconds(12);

        assertEquals(t0.plusSeconds(12), e.view(1).deadline());
        clock.advance(Duration.ofSeconds(6));                           // 11 s: still on show
        assertTrue(e.tick(clock.instant()).isEmpty());
        clock.advance(Duration.ofSeconds(1));
        e.tick(clock.instant());
        assertEquals(2, e.round());
    }

    @Test
    void aClashRoundKeepsItsThreeExtraSecondsWhenTheValueChanges() {
        Instant t0 = clock.instant();
        GameEngine e = engine(held, humans(3), hands(
                hand(card(11, 5), card(12, 8)), hand(card(21, 5), card(22, 3), card(23, 4)), hand(card(31, 1), card(32, 1), card(33, 1))));
        e.call(1, "power");
        e.call(2, "power");
        assertEquals(t0.plusSeconds(9), e.view(1).deadline());

        e.setResultHoldSeconds(10);

        assertEquals(t0.plusSeconds(13), e.view(1).deadline(), "10 + 3");
    }

    @Test
    void theNewValueAppliesToLaterHoldsAndIsRangeChecked() {
        GameEngine e = engine(held, humans(2), hands(hand(card(11, 9), card(12, 9), card(13, 9)), hand(card(21, 2), card(22, 3), card(23, 4))));
        e.setResultHoldSeconds(4);                                      // not in a hold: only changes the setting
        assertEquals(4, e.resultHoldSeconds());
        Instant t = clock.instant();
        e.call(1, "power");
        assertEquals(t.plusSeconds(4), e.view(1).deadline());

        assertThrows(IllegalArgumentException.class, () -> e.setResultHoldSeconds(21));
        assertThrows(IllegalArgumentException.class, () -> e.setResultHoldSeconds(-1));
        assertEquals(4, e.resultHoldSeconds(), "a refused value changes nothing");
    }
}
