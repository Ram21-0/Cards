package com.cards.game;

import com.cards.TestClock;
import com.cards.common.Card;
import com.cards.common.DifficultyLevel;
import com.cards.game.GameEvent.StatCalled;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static com.cards.game.TestKit.NO_CHOOSE;
import static com.cards.game.TestKit.STATS;
import static com.cards.game.TestKit.card;
import static com.cards.game.TestKit.hand;
import static com.cards.game.TestKit.hands;
import static com.cards.game.TestKit.only;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CPU seats inside the engine (RULES.md §3 step 3 and §7): the configured strategy plays, and CPUs race humans in a clash. */
class GameEngineCpuTest {

    private static final List<SeatSpec> HUMAN_THEN_CPU = List.of(SeatSpec.human(), SeatSpec.cpu(DifficultyLevel.hard));
    private static final List<SeatSpec> CPU_THEN_HUMAN = List.of(SeatSpec.cpu(DifficultyLevel.hard), SeatSpec.human());

    /** Two seats whose top cards tie on every stat, so round 1 is a clash. Seat 1 wins the clash by a mile (and so calls next). */
    private static List<List<Card>> clashingHands() {
        return hands(
                hand(card(11, 5, 5), card(12, 9, 1), card(13, 1, 9)),
                hand(card(21, 5, 5), card(22, 1, 9), card(23, 1, 9)));
    }

    private static GameEngine engine(List<SeatSpec> specs, int caller, TestClock clock, java.util.random.RandomGenerator rnd) {
        return TestKit.engine(NO_CHOOSE, specs, caller, clock, rnd, clashingHands());
    }

    // ---------------------------------------------------------------- strategies are configuration

    @Test
    void theConfiguredStrategyDecidesWhatTheCpuCalls() {
        // seat 1's card is the strongest in power and the worst in speed (lower wins), so a sensible CPU calls power
        List<List<Card>> hands = hands(hand(card(11, 10, 9), card(12, 1, 1)), hand(card(21, 2, 2), card(22, 2, 2)));

        for (CpuStrategy sensible : new CpuStrategy[]{CpuStrategy.DECK_PERCENTILE, CpuStrategy.OPPONENT_PERCENTILE, CpuStrategy.SMART}) {
            CpuProfile p = CpuProfile.of(sensible, 0);
            for (long seed = 0; seed < 10; seed++) {
                GameEngine e = GameEngine.withHands(STATS, CPU_THEN_HUMAN, NO_CHOOSE, CpuProfiles.of(p, p, p), hands, 1, 0,
                        new SplittableRandom(seed), TestClock.atNow());
                StatCalled called = only(e.pollEvents(), StatCalled.class).get(0);
                assertEquals("power", called.stat(), sensible + " seed " + seed);
            }
        }

        // the same seat under the random strategy calls both stats across seeds
        CpuProfile random = CpuProfile.of(CpuStrategy.RANDOM, 0);
        Set<String> called = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            GameEngine e = GameEngine.withHands(STATS, CPU_THEN_HUMAN, NO_CHOOSE, CpuProfiles.of(random, random, random), hands, 1, 0,
                    new SplittableRandom(seed), TestClock.atNow());
            called.add(only(e.pollEvents(), StatCalled.class).get(0).stat());
        }
        assertEquals(Set.of("power", "speed"), called);
    }

    @Test
    void eachDifficultyUsesItsOwnProfile() {
        List<List<Card>> hands = hands(hand(card(11, 10, 9), card(12, 1, 1)), hand(card(21, 2, 2), card(22, 2, 2)));
        CpuProfile sensible = CpuProfile.of(CpuStrategy.SMART, 0), random = CpuProfile.of(CpuStrategy.RANDOM, 0);
        CpuProfiles profiles = CpuProfiles.of(random, random, sensible);               // only HARD is sensible

        Set<String> easy = new HashSet<>(), hard = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            easy.add(only(GameEngine.withHands(STATS, List.of(SeatSpec.cpu(DifficultyLevel.easy), SeatSpec.human()), NO_CHOOSE, profiles,
                    hands, 1, 0, new SplittableRandom(seed), TestClock.atNow()).pollEvents(), StatCalled.class).get(0).stat());
            hard.add(only(GameEngine.withHands(STATS, CPU_THEN_HUMAN, NO_CHOOSE, profiles,
                    hands, 1, 0, new SplittableRandom(seed), TestClock.atNow()).pollEvents(), StatCalled.class).get(0).stat());
        }
        assertEquals(Set.of("power", "speed"), easy);
        assertEquals(Set.of("power"), hard);
    }

    // ---------------------------------------------------------------- clash race against a CPU

    @Test
    void aCpuInAClashCallsAfterOneAndAHalfToFourSeconds() {
        for (long seed = 0; seed < 60; seed++) {
            TestClock clock = TestClock.atNow();
            Instant start = clock.instant();
            GameEngine e = engine(CPU_THEN_HUMAN, 1, clock, new SplittableRandom(seed));
            e.pollEvents();
            assertEquals(Phase.CLASH_AWAIT_CALL, e.phase(), "round 1 ties and becomes a clash");
            Duration wait = Duration.between(start, e.nextWakeup().orElseThrow());
            assertTrue(wait.toMillis() >= 1500 && wait.toMillis() <= 4000, "seed " + seed + ": " + wait);
        }
    }

    @Test
    void aHumanWhoCallsBeforeTheCpuWinsTheRace() {
        TestClock clock = TestClock.atNow();
        GameEngine e = engine(CPU_THEN_HUMAN, 1, clock, new SplittableRandom(5));
        e.pollEvents();
        clock.advance(Duration.ofSeconds(1));                                          // the CPU needs at least 1.5 s
        List<GameEvent> events = e.call(2, "power");
        e.checkInvariants();
        assertEquals(new StatCalled(2, "power", false), only(events, StatCalled.class).get(0));
        // the human's call decided the race, so the CPU did not call (the first StatCalled is the human's)
    }

    @Test
    void aHumanWhoIsTooSlowLosesTheRaceToTheCpu() {
        TestClock clock = TestClock.atNow();
        GameEngine e = engine(CPU_THEN_HUMAN, 1, clock, new SplittableRandom(5));
        e.pollEvents();
        clock.advance(Duration.ofMillis(4001));                                        // beyond the CPU's longest delay, within the 5 s window
        assertThrows(GameRuleException.class, () -> e.call(2, "speed"), "the CPU called first, so the human's call is refused");
        List<GameEvent> events = e.pollEvents();
        StatCalled cpuCall = only(events, StatCalled.class).get(0);
        assertEquals(1, cpuCall.seat());
        assertFalse(cpuCall.auto());
        e.checkInvariants();
    }

    @Test
    void whenCpusAndHumansCallAtTheSameInstantTheLowestSeatWins() {
        // zeros(): every CPU delay is exactly 1.5 s, so a call can land on the very same instant
        TestClock clock = TestClock.atNow();
        GameEngine twoCpus = engine(List.of(SeatSpec.cpu(DifficultyLevel.hard), SeatSpec.cpu(DifficultyLevel.hard)), 1, clock, TestKit.zeros());
        twoCpus.pollEvents();
        clock.advance(Duration.ofMillis(1500));
        assertEquals(1, only(twoCpus.tick(clock.instant()), StatCalled.class).get(0).seat(), "CPU seat 1 before CPU seat 2");

        TestClock c2 = TestClock.atNow();
        GameEngine humanFirst = engine(List.of(SeatSpec.human(), SeatSpec.cpu(DifficultyLevel.hard)), 2, c2, TestKit.zeros());
        humanFirst.pollEvents();
        c2.advance(Duration.ofMillis(1500));                                           // the CPU (seat 2) is due right now
        assertEquals(new StatCalled(1, "power", false), only(humanFirst.call(1, "power"), StatCalled.class).get(0),
                "human seat 1 beats CPU seat 2 at the same instant");

        TestClock c3 = TestClock.atNow();
        GameEngine cpuFirst = engine(CPU_THEN_HUMAN, 1, c3, TestKit.zeros());
        cpuFirst.pollEvents();
        c3.advance(Duration.ofMillis(1500));
        assertThrows(GameRuleException.class, () -> cpuFirst.call(2, "power"), "CPU seat 1 beats human seat 2 at the same instant");
        assertEquals(1, only(cpuFirst.pollEvents(), StatCalled.class).get(0).seat());
    }

    @Test
    void ifNobodyCallsInTheClashWindowEvenWithACpuTheCpuCallsFirstBecauseItIsFasterThanTheWindow() {
        TestClock clock = TestClock.atNow();
        GameEngine e = engine(HUMAN_THEN_CPU, 2, clock, new SplittableRandom(9));
        e.pollEvents();
        assertEquals(Phase.CLASH_AWAIT_CALL, e.phase());
        clock.advance(Duration.ofSeconds(5));
        List<GameEvent> events = e.tick(clock.instant());
        StatCalled called = only(events, StatCalled.class).get(0);
        assertEquals(2, called.seat());                                                // the CPU (1.5-4 s) beat the 5 s timeout
        assertFalse(called.auto());
    }

    @Test
    void aClashWindowShorterThanTheCpuDelayEndsWithARandomStat() {
        GameSettings twoSeconds = new GameSettings(500, 0, 2, null);                    // the CPU waits 1.5-4 s: at worst it is too late
        int randomOutcomes = 0;
        for (long seed = 0; seed < 40; seed++) {
            TestClock c = TestClock.atNow();
            GameEngine e = TestKit.engine(twoSeconds, CPU_THEN_HUMAN, 1, c, new SplittableRandom(seed), clashingHands());
            e.pollEvents();
            c.advance(Duration.ofSeconds(2));
            StatCalled called = only(e.tick(c.instant()), StatCalled.class).get(0);
            if (called.seat() == null) {
                assertTrue(called.auto());
                randomOutcomes++;
            }
        }
        assertTrue(randomOutcomes > 0, "some CPU delays exceed 2 s, and then the server draws the stat");
    }
}
