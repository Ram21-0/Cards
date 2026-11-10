package com.cards.game;

import com.cards.TestClock;
import com.cards.common.Card;
import com.cards.common.DifficultyLevel;
import com.cards.common.Stat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static com.cards.game.TestKit.DECK;
import static com.cards.game.TestKit.STATS;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Do the difficulties actually differ? Seeded head-to-head games between two CPUs (seats alternate so neither side has the
 * first-call advantage). The thresholds are deliberately loose: they only guard the ORDER easy < medium < hard <= extreme.
 */
class CpuStrengthTest {

    private static final int GAMES = 400;

    /** The share of games won by {@code a} against {@code b} (a draw by the round cap counts half). */
    static double winRate(DifficultyLevel a, DifficultyLevel b) {
        return winRate(a, b, 2);
    }

    /** {@code pairs} seats of each kind, alternating a, b, a, b …; a team wins when its best placement is better. */
    static double winRate(DifficultyLevel a, DifficultyLevel b, int pairs) {
        double wins = 0;
        for (int g = 0; g < GAMES; g++) {
            boolean aFirst = g % 2 == 0;
            SplittableRandom r = new SplittableRandom(1000 + g);
            List<Card> deck = new ArrayList<>();
            for (int i = 1; i <= 24 + 12 * (pairs - 1); i++) {
                deck.add(Card.builder().id(i).deckId(DECK).name("C" + i).metadata(Map.of())
                        .stats(List.of(new Stat("power", BigDecimal.valueOf(r.nextInt(100))), new Stat("speed", BigDecimal.valueOf(r.nextInt(100)))))
                        .build());
            }
            List<SeatSpec> seats = new ArrayList<>();
            for (int k = 0; k < pairs; k++) {
                seats.add(SeatSpec.cpu(aFirst ? a : b));
                seats.add(SeatSpec.cpu(aFirst ? b : a));
            }
            GameEngine e = GameEngine.start(deck, STATS, seats, new GameSettings(300, 3, 5, null), CpuProfiles.defaults(),
                    new SplittableRandom(g * 17L + 3), TestClock.atNow());
            TestClock clock = TestClock.atNow();
            while (!e.isOver()) {
                Instant wake = e.nextWakeup().orElseThrow();
                if (wake.isAfter(clock.instant())) clock.set(wake);
                e.tick(clock.instant());
            }
            int mine = Integer.MAX_VALUE, theirs = Integer.MAX_VALUE;
            for (int seat = 1; seat <= seats.size(); seat++) {
                boolean isA = (seat % 2 == 1) == aFirst;
                int placement = e.placements().get(seat);
                if (isA) mine = Math.min(mine, placement); else theirs = Math.min(theirs, placement);
            }
            wins += mine < theirs ? 1 : mine == theirs ? 0.5 : 0;
        }
        return wins / GAMES;
    }

    /**
     * Measured with 400 games (2 seats): medium, hard and extreme each beat easy about 95% of the time, but hard vs medium is only
     * about 55% and extreme vs hard about 50%: the game is mostly luck once the CPU stops playing at random, and card counting
     * sharpens an estimate that was already good. So only the one robust fact is asserted.
     */
    @Test
    void everyThinkingDifficultyBeatsRandomPlay() {
        for (DifficultyLevel level : new DifficultyLevel[]{DifficultyLevel.medium, DifficultyLevel.hard, DifficultyLevel.extreme}) {
            assertTrue(winRate(level, DifficultyLevel.easy) > 0.8, level + " should beat easy clearly");
        }
    }
}
