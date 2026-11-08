package com.cards.game;

import com.cards.TestClock;
import com.cards.common.Card;
import com.cards.game.GameEvent.CardChosen;
import com.cards.game.GameEvent.ClashStarted;
import com.cards.game.GameEvent.GameOver;
import com.cards.game.GameEvent.PlayerEliminated;
import com.cards.game.GameEvent.PotCarried;
import com.cards.game.GameEvent.Revealed;
import com.cards.game.GameEvent.RoundStarted;
import com.cards.game.GameEvent.RoundWon;
import com.cards.game.GameEvent.StatCalled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static com.cards.game.TestKit.NO_CHOOSE;
import static com.cards.game.TestKit.card;
import static com.cards.game.TestKit.hand;
import static com.cards.game.TestKit.hands;
import static com.cards.game.TestKit.humans;
import static com.cards.game.TestKit.humansEngine;
import static com.cards.game.TestKit.ids;
import static com.cards.game.TestKit.only;
import static com.cards.game.TestKit.bottom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RULES.md §2-§6: rounds, clashes, CHOOSE, elimination and the round cap. Every step also checks that no card is lost. */
class GameEngineRulesTest {

    // ---------------------------------------------------------------- helpers

    /** Runs a command and checks the invariants afterwards. */
    private static List<GameEvent> call(GameEngine e, int seat, String stat) {
        List<GameEvent> events = e.call(seat, stat);
        e.checkInvariants();
        return events;
    }

    private static List<GameEvent> choose(GameEngine e, int seat, int cardId) {
        List<GameEvent> events = e.choose(seat, cardId);
        e.checkInvariants();
        return events;
    }

    /** A pile of {@code count} cards for a seat, ids {@code seat*100 + k}, top card first with the given power. */
    private static List<Card> pile(int seat, int topPower, int count) {
        List<Card> cards = new ArrayList<>();
        for (int k = 0; k < count; k++) cards.add(card(seat * 100 + k, k == 0 ? topPower : 1));
        return cards;
    }

    // ---------------------------------------------------------------- §2 a normal round

    @ParameterizedTest(name = "{0} players, seat {1} wins")
    @CsvSource({"2,1", "2,2", "3,1", "3,2", "3,3", "4,2", "5,5", "6,3", "7,1", "8,8", "8,4"})
    void normalRoundWinnerTakesEverythingInTheRightOrderAndCallsNext(int n, int winner) {
        List<List<Card>> hands = new ArrayList<>();
        for (int s = 1; s <= n; s++) hands.add(pile(s, s == winner ? 9 : s, 5));      // unique best: the winner's 9
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands);
        e.checkInvariants();
        assertEquals(Phase.AWAIT_CALL, e.phase());
        assertEquals(List.of(1), List.copyOf(e.actingSeats()));

        List<GameEvent> events = call(e, 1, "power");

        assertEquals(n, only(events, Revealed.class).get(0).cards().size());
        assertEquals(new RoundWon(winner, n), only(events, RoundWon.class).get(0));
        // won-card order: the winner's own card, then the others in seat order starting after the winner
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < n; i++) expected.add((((winner - 1 + i) % n) + 1) * 100);
        assertEquals(expected, bottom(e, winner, n));
        assertEquals(5 - 1 + n, e.handSize(winner));
        for (int s = 1; s <= n; s++) if (s != winner) assertEquals(4, e.handSize(s));
        // the winner calls next
        assertEquals(winner, e.callerSeat());
        assertEquals(2, e.round());
        assertEquals(Phase.AWAIT_CALL, e.phase());
    }

    @Test
    void afterARoundOnlyTheNextCallersCardIsInPlayAndThePotIsEmpty() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(pile(1, 9, 4), pile(2, 1, 4)));
        assertEquals(1, e.cardsInPlay(), "the caller's card is in play while the stat is chosen");
        call(e, 1, "power");
        assertEquals(1, e.cardsInPlay(), "the old round's cards are gone; the new caller has put its top card in play");
        assertEquals(0, e.potSize());
        assertEquals(8, e.handSize(1) + e.handSize(2));
    }

    @Test
    void aLowerWinsStatPicksTheSmallestValue() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(hand(card(11, 5, 8), card(12, 1)), hand(card(21, 5, 2), card(22, 1))));
        List<GameEvent> events = call(e, 1, "speed");
        assertEquals(2, only(events, RoundWon.class).get(0).seat());
    }

    @Test
    void onlyTheCallerMayCallAndOnlyKnownStats() {
        GameEngine e = humansEngine(NO_CHOOSE, 2, hands(pile(1, 5, 4), pile(2, 5, 4), pile(3, 1, 4)));
        assertThrows(GameRuleException.class, () -> e.call(1, "power"));
        assertThrows(GameRuleException.class, () -> e.call(2, "charisma"));
        assertThrows(GameRuleException.class, () -> e.call(9, "power"));
        assertThrows(GameRuleException.class, () -> e.choose(2, 200));              // nothing to choose now
    }

    @Test
    void uneven_deals_give_some_seats_one_extra_card() {
        List<Card> deck = new ArrayList<>();
        for (int i = 1; i <= 10; i++) deck.add(card(i, i));
        GameEngine e = GameEngine.start(deck, TestKit.STATS, humans(3), NO_CHOOSE, new SplittableRandom(5), TestClock.atNow());
        e.checkInvariants();
        List<Integer> sizes = List.of(e.handSize(1), e.handSize(2), e.handSize(3));
        assertEquals(10, sizes.stream().mapToInt(Integer::intValue).sum());
        assertEquals(List.of(3, 3, 4), sizes.stream().sorted().toList());
        assertEquals(1, e.round());
    }

    @Test
    void aFullDeckIsDealtWithoutDuplicates() {
        List<Card> deck = new ArrayList<>();
        for (int i = 1; i <= 54; i++) deck.add(card(i, i % 7));
        GameEngine e = GameEngine.start(deck, TestKit.STATS, humans(8), NO_CHOOSE, new SplittableRandom(9), TestClock.atNow());
        e.checkInvariants();
        int most = 0, least = 99;
        for (int s = 1; s <= 8; s++) { most = Math.max(most, e.handSize(s)); least = Math.min(least, e.handSize(s)); }
        assertEquals(1, most - least);
    }

    @Test
    void startNeedsEnoughSeatsAndCards() {
        List<Card> deck = List.of(card(1, 1), card(2, 2), card(3, 3));
        assertThrows(IllegalArgumentException.class, () -> GameEngine.start(deck, TestKit.STATS, humans(1), NO_CHOOSE, new SplittableRandom(1), TestClock.atNow()));
        assertThrows(IllegalArgumentException.class, () -> GameEngine.start(deck, TestKit.STATS, humans(9), NO_CHOOSE, new SplittableRandom(1), TestClock.atNow()));
        assertThrows(IllegalArgumentException.class, () -> GameEngine.start(deck, TestKit.STATS, humans(4), NO_CHOOSE, new SplittableRandom(1), TestClock.atNow()));
    }

    @Test
    void theOpeningEventsAreAvailableAfterStart() {
        GameEngine e = GameEngine.withHands(TestKit.STATS, humans(3), NO_CHOOSE, hands(pile(1, 5, 3), pile(2, 5, 3), pile(3, 5, 3)),
                2, 0, new SplittableRandom(1), TestClock.atNow());
        List<GameEvent> opening = e.pollEvents();
        assertEquals(new RoundStarted(1, 2, List.of(1, 2, 3)), opening.get(0));
        assertTrue(e.pollEvents().isEmpty(), "events are returned once");
    }

    // ---------------------------------------------------------------- §3 clash

    @Test
    void threeWayTieThenRepeatedClashWhereOnlyTheTiedPlayersGoOn() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(
                hand(card(11, 5), card(12, 7), card(13, 9), card(14, 1), card(15, 1), card(16, 1)),
                hand(card(21, 5), card(22, 7), card(23, 3), card(24, 1), card(25, 1), card(26, 1)),
                hand(card(31, 5), card(32, 2), card(33, 1), card(34, 1), card(35, 1), card(36, 1)),
                hand(card(41, 1), card(42, 1), card(43, 1), card(44, 1), card(45, 1), card(46, 1))));

        // level 1: seats 1, 2, 3 tie on 5 — the bystander's card (seat 4) goes into the pot too
        List<GameEvent> level1 = call(e, 1, "power");
        assertEquals(new ClashStarted(List.of(1, 2, 3), 1, 4), only(level1, ClashStarted.class).get(0));
        assertEquals(Phase.CLASH_AWAIT_CALL, e.phase());
        assertEquals(List.of(1, 2, 3), List.copyOf(e.actingSeats()));
        assertThrows(GameRuleException.class, () -> e.call(4, "power"), "a bystander cannot call in the clash");

        // level 2: seat 3 wins the race; next cards are 7, 7, 2 → seats 1 and 2 tie again, seat 3's card joins the pot
        List<GameEvent> level2 = call(e, 3, "power");
        assertEquals(new StatCalled(3, "power", false), only(level2, StatCalled.class).get(0));
        assertEquals(new ClashStarted(List.of(1, 2), 2, 7), only(level2, ClashStarted.class).get(0));
        assertEquals(List.of(1, 2), List.copyOf(e.actingSeats()), "only the tied players repeat");
        assertThrows(GameRuleException.class, () -> e.call(3, "power"));
        assertThrows(GameRuleException.class, () -> e.call(4, "power"));

        // level 3: next cards 9 vs 3 → seat 1 takes the whole pot plus the cards just revealed
        List<GameEvent> level3 = call(e, 1, "power");
        assertEquals(new RoundWon(1, 9), only(level3, RoundWon.class).get(0));
        assertEquals(List.of(13, 23, 11, 21, 31, 41, 12, 22, 32), bottom(e, 1, 9),
                "own revealed card, the other revealed card, then the pot in the order it was built");
        assertEquals(12, e.handSize(1));
        assertEquals(3, e.handSize(2));
        assertEquals(4, e.handSize(3));
        assertEquals(5, e.handSize(4));
        assertEquals(1, e.callerSeat(), "the winner calls next");
        assertEquals(0, e.potSize());
    }

    @Test
    void aTiedPlayerWithNoNextCardDropsOutAndTheOtherWinsByDefault() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(
                hand(card(11, 5)),                                               // one card only: nothing to take next
                hand(card(21, 5), card(22, 1), card(23, 1)),
                hand(card(31, 1), card(32, 1), card(33, 1))));
        List<GameEvent> events = call(e, 1, "power");

        assertEquals(new ClashStarted(List.of(1, 2), 1, 3), only(events, ClashStarted.class).get(0));
        assertEquals(new RoundWon(2, 3), only(events, RoundWon.class).get(0), "seat 2 takes the pot (3 cards) by default");
        assertEquals(new PlayerEliminated(1, 3), only(events, PlayerEliminated.class).get(0));
        assertEquals(5, e.handSize(2));
        assertEquals(2, e.callerSeat());
        assertEquals(Map.of(1, 3), e.placements());
        assertEquals(2, e.round());
    }

    @Test
    void whenNobodyInTheClashCanContinueThePotCarriesToALaterWinner() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(
                hand(card(11, 5)),
                hand(card(21, 5)),
                hand(card(31, 1), card(32, 9), card(33, 1)),
                hand(card(41, 1), card(42, 2), card(43, 1))));
        List<GameEvent> events = call(e, 1, "power");

        assertEquals(new PotCarried(4), only(events, PotCarried.class).get(0));
        assertEquals(List.of(new PlayerEliminated(1, 3), new PlayerEliminated(2, 3)), only(events, PlayerEliminated.class),
                "players knocked out in the same round share a placement");
        assertEquals(4, e.potSize(), "the pot waits");
        assertEquals(3, e.callerSeat(), "the caller passes to the next active seat clockwise");
        assertEquals(new RoundStarted(2, 3, List.of(3, 4)), only(events, RoundStarted.class).get(0));

        List<GameEvent> next = call(e, 3, "power");                              // 9 beats 2
        assertEquals(new RoundWon(3, 6), only(next, RoundWon.class).get(0));
        assertEquals(List.of(32, 42, 11, 21, 31, 41), bottom(e, 3, 6), "revealed cards first, then the carried pot");
        assertEquals(0, e.potSize());
    }

    @Test
    void ifEveryoneRunsOutTogetherWithAPotCarriedItIsADraw() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(hand(card(11, 5)), hand(card(21, 5))));
        List<GameEvent> events = call(e, 1, "power");
        assertTrue(e.isOver());
        assertEquals(Map.of(1, 1, 2, 1), only(events, GameOver.class).get(0).placements());
        assertFalse(only(events, GameOver.class).get(0).endedByRoundCap());
    }

    @Test
    void aClashTimeoutPicksARandomStatForEveryone() {
        TestClock clock = TestClock.atNow();
        GameEngine e = TestKit.engine(NO_CHOOSE, humans(2), 1, clock, new SplittableRandom(3), hands(
                hand(card(11, 5), card(12, 1), card(13, 1)), hand(card(21, 5), card(22, 2), card(23, 2))));
        call(e, 1, "power");                                                    // tie → clash, nobody calls
        assertEquals(Phase.CLASH_AWAIT_CALL, e.phase());

        clock.advance(Duration.ofMillis(4999));
        assertTrue(e.tick(clock.instant()).isEmpty(), "not yet");
        clock.advance(Duration.ofMillis(1));
        List<GameEvent> events = e.tick(clock.instant());
        e.checkInvariants();

        StatCalled called = only(events, StatCalled.class).get(0);
        assertEquals(null, called.seat(), "nobody called");
        assertTrue(called.auto());
        assertTrue(called.stat().equals("power") || called.stat().equals("speed"));
    }

    @Test
    void theRandomClashStatIsReproducibleFromTheSeedAndReallyRandom() {
        List<String> picks = new ArrayList<>();
        for (long seed = 0; seed < 40; seed++) picks.add(clashTimeoutStat(seed));
        for (long seed = 0; seed < 40; seed++) assertEquals(picks.get((int) seed), clashTimeoutStat(seed), "same seed, same stat");
        assertTrue(picks.contains("power") && picks.contains("speed"), "different seeds give both stats: " + picks);
    }

    private static String clashTimeoutStat(long seed) {
        TestClock clock = TestClock.atNow();
        GameEngine e = TestKit.engine(NO_CHOOSE, humans(2), 1, clock, new SplittableRandom(seed), hands(
                hand(card(11, 5), card(12, 1), card(13, 1)), hand(card(21, 5), card(22, 2), card(23, 2))));
        e.call(1, "power");
        clock.advance(Duration.ofSeconds(5));
        return only(e.tick(clock.instant()), StatCalled.class).get(0).stat();
    }

    @Test
    void theFirstCallInAClashWinsTheRace() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(
                hand(card(11, 5), card(12, 1), card(13, 1)), hand(card(21, 5), card(22, 9), card(23, 1))));
        call(e, 1, "power");                                                    // tie → clash
        List<GameEvent> events = call(e, 2, "power");                           // seat 2 is first
        assertEquals(new StatCalled(2, "power", false), only(events, StatCalled.class).get(0));
        // seat 2 won the clash (9 beats 1) and now calls; seat 1's late call is refused
        assertThrows(GameRuleException.class, () -> e.call(1, "speed"));
        assertEquals(2, e.callerSeat());
    }

    @Test
    void aClashCallAfterTheDeadlineIsTooLate() {
        TestClock clock = TestClock.atNow();
        GameEngine e = TestKit.engine(NO_CHOOSE, humans(2), 1, clock, new SplittableRandom(4), hands(
                hand(card(11, 5), card(12, 1), card(13, 1)), hand(card(21, 5), card(22, 9), card(23, 1))));
        call(e, 1, "power");
        clock.advance(Duration.ofSeconds(5));                                   // exactly the clash window
        e.call(2, "power").size();                                              // the timeout fires first: stat is random, auto
        List<GameEvent> events = e.pollEvents();                                // (call() drained its own events; nothing lost)
        assertTrue(events.isEmpty());
    }

    // ---------------------------------------------------------------- §5 elimination and ending

    @Test
    void theCallerCanBeEliminatedAndTheRoundWinnerCallsAnyway() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(
                hand(card(11, 1)),                                              // the caller's only card
                hand(card(21, 9), card(22, 1), card(23, 1)),
                hand(card(31, 5), card(32, 1), card(33, 1))));
        List<GameEvent> events = call(e, 1, "power");
        assertEquals(new RoundWon(2, 3), only(events, RoundWon.class).get(0));
        assertEquals(new PlayerEliminated(1, 3), only(events, PlayerEliminated.class).get(0));
        assertEquals(2, e.callerSeat());
        assertEquals(new RoundStarted(2, 2, List.of(2, 3)), only(events, RoundStarted.class).get(0));
        assertFalse(e.isOver());
    }

    @Test
    void playersEliminatedInTheSameRoundShareAPlacement() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(
                hand(card(11, 1)), hand(card(21, 2)),
                hand(card(31, 9), card(32, 1), card(33, 1)),
                hand(card(41, 5), card(42, 1), card(43, 1))));
        List<GameEvent> events = call(e, 1, "power");
        assertEquals(List.of(new PlayerEliminated(1, 3), new PlayerEliminated(2, 3)), only(events, PlayerEliminated.class));
        assertEquals(Map.of(1, 3, 2, 3), e.placements());
    }

    @Test
    void theGameEndsWhenOnePlayerHoldsAllTheCardsAndPlacementsFollowEliminationOrder() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(
                hand(card(11, 1)), hand(card(21, 2), card(22, 1)), hand(card(31, 9), card(32, 1), card(33, 1))));
        call(e, 1, "power");                                                    // seat 1 out (placement 3)
        assertFalse(e.isOver());
        assertEquals(3, e.callerSeat());
        List<GameEvent> last = call(e, 3, "power");                             // seat 3: 9 vs seat 2's top card
        // seat 2's top card is now 2 or the won cards... just play until over
        while (!e.isOver()) call(e, e.callerSeat(), "power");
        GameOver over = only(last.isEmpty() ? e.pollEvents() : last, GameOver.class).stream().findFirst().orElse(null);
        assertEquals(3, e.placements().get(1));
        assertEquals(1, e.placements().values().stream().min(Integer::compare).orElseThrow());
        assertEquals(3, e.placements().size());
    }

    @Test
    void commandsAreRefusedOnceTheGameIsOver() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(hand(card(11, 5)), hand(card(21, 5))));
        call(e, 1, "power");
        assertTrue(e.isOver());
        assertEquals(Phase.GAME_OVER, e.phase());
        assertThrows(GameRuleException.class, () -> e.call(1, "power"));
        assertThrows(GameRuleException.class, () -> e.choose(1, 11));
        assertTrue(e.tick(TestClock.atNow().instant()).isEmpty());
        assertTrue(e.actingSeats().isEmpty());
        assertTrue(e.nextWakeup().isEmpty());
    }

    // ---------------------------------------------------------------- §6 round cap

    private static GameEngine atRound(int roundsPlayed, GameSettings settings, List<List<Card>> hands, int caller) {
        return GameEngine.withHands(TestKit.STATS, humans(hands.size()), settings, hands, caller, roundsPlayed,
                new SplittableRandom(1), TestClock.atNow());
    }

    private static final GameSettings CAP_20 = new GameSettings(20, 0, 5, null);

    @Test
    void theRoundCapEndsTheGameAndEqualTopCountsSharePlacementOne() {
        GameEngine e = atRound(19, CAP_20, hands(
                hand(card(11, 5)), hand(card(21, 5)),
                hand(card(31, 1), card(32, 1), card(33, 1), card(34, 1)),
                hand(card(41, 1), card(42, 1), card(43, 1), card(44, 1))), 1);
        List<GameEvent> events = call(e, 1, "power");                           // 1 and 2 clash and run out: the pot carries
        assertTrue(e.isOver());
        GameOver over = only(events, GameOver.class).get(0);
        assertTrue(over.endedByRoundCap());
        assertEquals(Map.of(1, 3, 2, 3, 3, 1, 4, 1), over.placements(), "3 and 4 hold 3 cards each: both first");
    }

    @Test
    void atTheCapPlayersAreRankedByCardCount() {
        GameEngine e = atRound(19, CAP_20, hands(
                hand(card(11, 9), card(12, 1)),                                 // winner: 2 - 1 + 4 = 5
                hand(card(21, 1), card(22, 1), card(23, 1), card(24, 1), card(25, 1)),   // 5 - 1 = 4
                hand(card(31, 1), card(32, 1), card(33, 1), card(34, 1)),                // 3
                hand(card(41, 1), card(42, 1), card(43, 1))), 1);                        // 2
        List<GameEvent> events = call(e, 1, "power");
        assertEquals(Map.of(1, 1, 2, 2, 3, 3, 4, 4), only(events, GameOver.class).get(0).placements());
    }

    @Test
    void equalCountsBelowTheTopAlsoShareAPlacement() {
        GameEngine e = atRound(19, CAP_20, hands(
                hand(card(11, 9), card(12, 1)),                                 // 5
                hand(card(21, 1), card(22, 1), card(23, 1), card(24, 1)),       // 3
                hand(card(31, 1), card(32, 1), card(33, 1), card(34, 1)),       // 3
                hand(card(41, 1), card(42, 1), card(43, 1), card(44, 1))), 1);  // 3
        assertEquals(Map.of(1, 1, 2, 2, 3, 2, 4, 2), only(call(e, 1, "power"), GameOver.class).get(0).placements());
    }

    @Test
    void oneRoundBeforeTheCapTheGameGoesOn() {
        GameEngine e = atRound(18, CAP_20, hands(hand(card(11, 9), card(12, 2)), hand(card(21, 1), card(22, 1), card(23, 1))), 1);
        call(e, 1, "power");
        assertFalse(e.isOver());
        assertEquals(20, e.round(), "the 20th and last round has started");
        call(e, e.callerSeat(), "power");
        assertTrue(e.isOver());
    }

    @Test
    void aCarriedPotCountsForNobodyAtTheCap() {
        GameEngine e = atRound(19, CAP_20, hands(
                hand(card(11, 5)), hand(card(21, 5)), hand(card(31, 1), card(32, 1)), hand(card(41, 1), card(42, 1))), 1);
        call(e, 1, "power");
        assertTrue(e.isOver());
        assertEquals(4, e.potSize(), "the 4 carried cards belong to nobody");
    }

    // ---------------------------------------------------------------- misc

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8})
    void everySeatCountPlaysThroughANormalRoundWithCardsConserved(int n) {
        List<List<Card>> hands = new ArrayList<>();
        for (int s = 1; s <= n; s++) hands.add(pile(s, s, 6));
        GameEngine e = humansEngine(NO_CHOOSE, n, hands);
        e.checkInvariants();
        call(e, n, "power");
        assertEquals(n, e.callerSeat(), "the highest top card wins");
    }
}
