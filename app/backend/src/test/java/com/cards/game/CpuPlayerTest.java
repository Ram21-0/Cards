package com.cards.game;

import com.cards.common.Card;
import com.cards.common.DifficultyLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static com.cards.game.TestKit.STATS;
import static com.cards.game.TestKit.card;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CPU strategies (RULES.md §7): what each one prefers, in situations built so that the strategies disagree.
 * Stat index 0 is "power" (higher wins), index 1 is "speed" (LOWER wins).
 */
class CpuPlayerTest {

    private static final int POWER = 0;
    private static final int SPEED = 1;

    private static CpuPlayer cpu(CpuStrategy strategy, double noise, long seed, List<Card> deck) {
        CpuProfile p = CpuProfile.of(strategy, noise);
        return new CpuPlayer(STATS, deck, CpuProfiles.of(p, p, p), new SplittableRandom(seed));
    }

    private static CpuPlayer.Situation sit(List<Card> own, int opponents) {
        return new CpuPlayer.Situation(own, opponents, 0);
    }

    private static List<Card> all(List<Card>... groups) {
        List<Card> deck = new ArrayList<>();
        for (List<Card> g : groups) deck.addAll(g);
        return deck;
    }

    // ---------------------------------------------------------------- random

    @Test
    void randomPlaysEveryStatAndEveryCardEventually() {
        List<Card> hand = List.of(card(1, 5, 5), card(2, 6, 4), card(3, 7, 3));
        CpuPlayer cpu = cpu(CpuStrategy.RANDOM, 0, 1, hand);
        Set<Integer> stats = new HashSet<>(), forCaller = new HashSet<>(), forStat = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            stats.add(cpu.pickStat(hand.get(0), DifficultyLevel.easy, sit(hand, 1)));
            forCaller.add(cpu.pickCardForCaller(hand, DifficultyLevel.easy, sit(hand, 1)));
            forStat.add(cpu.pickCardForStat(hand, POWER, DifficultyLevel.easy, sit(hand, 1)));
        }
        assertEquals(Set.of(0, 1), stats);
        assertEquals(Set.of(0, 1, 2), forCaller);
        assertEquals(Set.of(0, 1, 2), forStat);
    }

    // ---------------------------------------------------------------- deck percentile

    @Test
    void deckPercentilePicksTheStatWhereTheCardBeatsTheLargestShareOfTheDeck() {
        Card x = card(1, 10, 9);                                           // top power, worst speed (lower wins)
        List<Card> deck = all(List.of(x), List.of(card(2, 1, 1), card(3, 2, 2), card(4, 3, 3), card(5, 4, 4)));
        CpuPlayer cpu = cpu(CpuStrategy.DECK_PERCENTILE, 0, 1, deck);
        assertEquals(POWER, cpu.pickStat(x, DifficultyLevel.hard, sit(List.of(x), 1)));
        Card y = card(9, 0, 0);                                            // worst power, best speed
        CpuPlayer cpu2 = cpu(CpuStrategy.DECK_PERCENTILE, 0, 1, all(List.of(y), deck.subList(1, 5)));
        assertEquals(SPEED, cpu2.pickStat(y, DifficultyLevel.hard, sit(List.of(y), 1)));
    }

    // ---------------------------------------------------------------- opponent percentile (the CPU's own cards do not count)

    /** X is mediocre. The CPU also holds five weak-power cards; the four opponent-side cards are strong in power, slow in speed. */
    private static List<Card> ownHandInflatesPower() {
        List<Card> own = new ArrayList<>(List.of(card(1, 5, 5)));
        for (int i = 0; i < 5; i++) own.add(card(10 + i, 1, 5));           // weak power: X "beats" them, but they cannot be opponents
        return own;
    }

    private static List<Card> strongPowerSlowSpeedOpponents() {
        List<Card> others = new ArrayList<>();
        for (int i = 0; i < 4; i++) others.add(card(20 + i, 9, 7));        // beat X on power; X's speed 5 beats their 7
        return others;
    }

    @Test
    void opponentPercentileIgnoresTheCpusOwnCardsWhereDeckPercentileDoesNot() {
        List<Card> own = ownHandInflatesPower();
        List<Card> deck = all(own, strongPowerSlowSpeedOpponents());
        Card x = own.get(0);

        // over the whole deck power looks best (5/9 vs 4/9) because the CPU's own weak cards pad it
        assertEquals(POWER, cpu(CpuStrategy.DECK_PERCENTILE, 0, 1, deck).pickStat(x, DifficultyLevel.hard, sit(own, 1)));
        // against only the cards the CPU does not hold, power beats nobody and speed beats everybody
        assertEquals(SPEED, cpu(CpuStrategy.OPPONENT_PERCENTILE, 0, 1, deck).pickStat(x, DifficultyLevel.hard, sit(own, 1)));
    }

    @Test
    void opponentPercentileChoosesTheCardThatBeatsTheMostOpponentCards() {
        Card weak = card(1, 2, 5), strong = card(2, 8, 5);
        List<Card> opponents = List.of(card(20, 5, 5), card(21, 5, 5), card(22, 5, 5), card(23, 9, 5));
        List<Card> own = List.of(weak, strong);
        CpuPlayer cpu = cpu(CpuStrategy.OPPONENT_PERCENTILE, 0, 1, all(own, opponents));
        assertEquals(1, cpu.pickCardForStat(own, POWER, DifficultyLevel.hard, sit(own, 1)));
    }

    // ---------------------------------------------------------------- smart

    @Test
    void smartCountsATieAsHalfAWinWhilePercentileDoesNot() {
        // on power the opponents tie half the time and lose half the time (never beat X); on speed X beats 60% but loses 40%
        Card x = card(1, 5, 5);
        List<Card> pool = new ArrayList<>();
        for (int i = 0; i < 5; i++) pool.add(card(20 + i, 5, 9));          // tie on power, X faster
        pool.add(card(25, 1, 9));                                          // X stronger and faster
        for (int i = 0; i < 4; i++) pool.add(card(26 + i, 1, 1));          // X stronger but slower (loses speed)
        List<Card> deck = all(List.of(x), pool);

        assertEquals(SPEED, cpu(CpuStrategy.OPPONENT_PERCENTILE, 0, 1, deck).pickStat(x, DifficultyLevel.hard, sit(List.of(x), 1)),
                "percentile: 0.6 beats 0.5");
        assertEquals(POWER, cpu(CpuStrategy.SMART, 0, 1, deck).pickStat(x, DifficultyLevel.hard, sit(List.of(x), 1)),
                "smart: never losing (0.5 + half of 0.5 = 0.75) beats 0.6");
    }

    @Test
    void smartSacrificesTheWeakCardInAHopelessRoundAndKeepsTheStrongOne() {
        // every opponent card beats both of ours on power, so this round is lost whatever we play
        Card keeper = card(1, 5, 1);                                       // power 5 loses, but speed 1 is the best in the game: a card to keep
        Card scrap = card(2, 4, 9);                                        // loses everything: nothing to lose by giving it away
        List<Card> own = List.of(keeper, scrap);
        List<Card> opponents = new ArrayList<>();
        for (int i = 0; i < 4; i++) opponents.add(card(20 + i, 9, 5));
        List<Card> deck = all(own, opponents);

        Set<Integer> picked = new HashSet<>();
        CpuPlayer percentile = cpu(CpuStrategy.OPPONENT_PERCENTILE, 0, 1, deck);
        for (int i = 0; i < 100; i++) picked.add(percentile.pickCardForStat(own, POWER, DifficultyLevel.hard, sit(own, 1)));
        assertEquals(Set.of(0, 1), picked, "when every card is hopeless the percentile strategies pick at random");
        assertEquals(1, cpu(CpuStrategy.SMART, 0, 1, deck).pickCardForStat(own, POWER, DifficultyLevel.hard, sit(own, 1)),
                "smart gives away the scrap card");
    }

    @Test
    void percentileStillPlaysTheBestCardWhenAnyCardHasAChance() {
        Card weak = card(1, 1, 5), better = card(2, 6, 5);
        List<Card> own = List.of(weak, better);
        List<Card> opponents = List.of(card(20, 5, 5), card(21, 9, 5), card(22, 9, 5));   // 'better' beats one of three, 'weak' none
        CpuPlayer percentile = cpu(CpuStrategy.OPPONENT_PERCENTILE, 0, 1, all(own, opponents));
        for (int i = 0; i < 20; i++) assertEquals(1, percentile.pickCardForStat(own, POWER, DifficultyLevel.hard, sit(own, 1)));
    }

    @Test
    void smartKeepsAStrongCardThatCanTieWhenTheCardsBehindItCanWinTheClash() {
        // power: X can only tie (5 vs 5,5,9,9); Y and the others cannot even tie. X is a keeper (best speed in the deck).
        Card x = card(1, 5, 1), y = card(2, 1, 9), z = card(3, 1, 1), w = card(4, 1, 1);
        List<Card> opponents = List.of(card(20, 5, 5), card(21, 5, 5), card(22, 9, 5), card(23, 9, 5));
        List<Card> strongBehind = List.of(x, y, z, w);
        assertEquals(0, cpu(CpuStrategy.SMART, 0, 1, all(strongBehind, opponents))
                        .pickCardForStat(strongBehind, POWER, DifficultyLevel.hard, sit(strongBehind, 1)),
                "strong cards behind it make the tie worth playing: the strong X is not given away");

        Card y2 = card(5, 1, 9);
        List<Card> weakBehind = List.of(x, y, y2);
        int given = cpu(CpuStrategy.SMART, 0, 1, all(weakBehind, opponents)).pickCardForStat(weakBehind, POWER, DifficultyLevel.hard, sit(weakBehind, 1));
        assertTrue(given != 0, "nothing strong behind it: the tie is a losing bet and a scrap card is given away instead");
    }

    @Test
    void countingMeasuresEachOpponentAgainstTheCardsThatOpponentCanStillPlay() {
        // A (power 8, a keeper: best speed) and B (power 6, scrap). Unheld cards: 7, 9, 9, 9 - but the three 9s were revealed and
        // are not in anybody's hand. Smart still fears them and sacrifices the scrap card; counting knows the opponent can only play the 7.
        Card a = card(1, 8, 1), b = card(2, 6, 9);
        Card seven = card(20, 7, 5);
        List<Card> own = List.of(a, b);
        List<Card> deck = all(own, List.of(seven, card(21, 9, 5), card(22, 9, 5), card(23, 9, 5)));

        assertEquals(1, cpu(CpuStrategy.SMART, 0, 1, deck).pickCardForStat(own, POWER, DifficultyLevel.hard, sit(own, 1)),
                "smart: the keeper is unlikely to win against all the unheld cards, so the scrap card is given away");
        CpuPlayer.Situation counted = new CpuPlayer.Situation(own, 1, 0, List.of(List.of(seven)));
        assertEquals(0, cpu(CpuStrategy.COUNTING, 0, 1, deck).pickCardForStat(own, POWER, DifficultyLevel.extreme, counted),
                "counting: the only card the opponent can play is beaten by the keeper, so it plays the keeper and wins");
    }

    @Test
    void countingWithoutKnowledgePlaysLikeSmart() {
        Card a = card(1, 8, 1), b = card(2, 6, 9);
        List<Card> own = List.of(a, b);
        List<Card> deck = all(own, List.of(card(20, 7, 5), card(21, 9, 5), card(22, 9, 5), card(23, 9, 5)));
        assertEquals(cpu(CpuStrategy.SMART, 0, 1, deck).pickCardForStat(own, POWER, DifficultyLevel.hard, sit(own, 1)),
                cpu(CpuStrategy.COUNTING, 0, 1, deck).pickCardForStat(own, POWER, DifficultyLevel.extreme, sit(own, 1)));
    }

    @Test
    void smartPlaysTheCardThatWinsWhenARoundCanBeWon() {
        Card winner = card(1, 10, 5), other = card(2, 2, 5);
        List<Card> own = List.of(other, winner);
        List<Card> opponents = List.of(card(20, 5, 5), card(21, 6, 5), card(22, 4, 5));
        assertEquals(1, cpu(CpuStrategy.SMART, 0, 1, all(own, opponents)).pickCardForStat(own, POWER, DifficultyLevel.hard, sit(own, 1)));
    }

    @Test
    void smartTakesTheWholeFieldAndThePotIntoAccount() {
        // a risky card still has the best chance; with a big pot nothing changes about WHICH card wins, but the sacrifice
        // threshold does: a lost round is no worse than giving the weak card away
        Card keeper = card(1, 6, 1), scrap = card(2, 3, 9);
        List<Card> own = List.of(keeper, scrap);
        List<Card> opponents = new ArrayList<>();
        for (int i = 0; i < 6; i++) opponents.add(card(20 + i, 5 + i, 5));   // powers 5..10: the keeper beats only one of six
        CpuPlayer cpu = cpu(CpuStrategy.SMART, 0, 1, all(own, opponents));
        // 1 opponent: keeper has p = 1/6 → EV 1/6·1 − 5/6·(1+keep) < scrap's −(1+0): still plays the keeper? check both ways
        int oneOpponent = cpu.pickCardForStat(own, POWER, DifficultyLevel.hard, new CpuPlayer.Situation(own, 1, 0));
        int sevenOpponentsBigPot = cpu.pickCardForStat(own, POWER, DifficultyLevel.hard, new CpuPlayer.Situation(own, 7, 20));
        assertTrue(oneOpponent == 0 || oneOpponent == 1);
        assertEquals(1, sevenOpponentsBigPot, "against seven opponents the keeper is almost certain to lose: give the scrap card away");
    }

    @Test
    void theCallerChoosesTheCardWithTheStrongestSingleStat() {
        Card allRounder = card(1, 5, 7), specialist = card(2, 10, 9);       // mid-range everywhere vs. power 10 that beats everything
        List<Card> own = List.of(allRounder, specialist);
        List<Card> opponents = List.of(card(20, 6, 6), card(21, 7, 7), card(22, 8, 8));
        for (CpuStrategy strategy : new CpuStrategy[]{CpuStrategy.DECK_PERCENTILE, CpuStrategy.OPPONENT_PERCENTILE, CpuStrategy.SMART}) {
            assertEquals(1, cpu(strategy, 0, 1, all(own, opponents)).pickCardForCaller(own, DifficultyLevel.hard, sit(own, 1)), strategy.name());
        }
    }

    // ---------------------------------------------------------------- noise

    @Test
    void noiseMakesTheCpuSometimesPickTheSecondBestAndZeroNoiseNeverDoes() {
        // against 20 opponent cards the first card beats 11 of them (0.55) and the second 9 (0.45)
        List<Card> opponents = new ArrayList<>();
        for (int i = 0; i < 20; i++) opponents.add(card(100 + i, i < 9 ? 1 : i < 11 ? 5 : 9, 5));
        Card first = card(1, 5, 5), second = card(2, 4, 5);
        // first (power 5) beats the power-1 cards... build so that shares are 0.55 vs 0.45:
        List<Card> own = List.of(second, first);
        List<Card> deck = all(own, opponents);

        CpuPlayer exact = cpu(CpuStrategy.OPPONENT_PERCENTILE, 0, 1, deck);
        CpuPlayer noisy = cpu(CpuStrategy.OPPONENT_PERCENTILE, 0.5, 1, deck);
        Set<Integer> exactPicks = new HashSet<>(), noisyPicks = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            exactPicks.add(exact.pickCardForStat(own, POWER, DifficultyLevel.hard, sit(own, 1)));
            noisyPicks.add(noisy.pickCardForStat(own, POWER, DifficultyLevel.hard, sit(own, 1)));
        }
        assertEquals(1, exactPicks.size(), "no noise: always the same choice");
        assertEquals(Set.of(0, 1), noisyPicks, "noise: both cards get played");
    }

    // ---------------------------------------------------------------- the delay in a clash race

    @Test
    void aCpuWaitsBetweenOneAndAHalfAndFourSecondsBeforeCallingInAClash() {
        CpuPlayer cpu = cpu(CpuStrategy.SMART, 0, 7, List.of(card(1, 1, 1), card(2, 2, 2)));
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < 5000; i++) {
            long d = cpu.clashDelayMillis();
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        assertTrue(min >= 1500 && max <= 4000, "delays stay in 1.5-4.0 s: " + min + ".." + max);
        assertTrue(min < 1600 && max > 3900, "and use the whole range: " + min + ".." + max);
    }

    // ---------------------------------------------------------------- names and profiles

    @Test
    void strategiesAreNamedInConfigurationLikeThis() {
        assertEquals(CpuStrategy.RANDOM, CpuStrategy.fromConfig("random"));
        assertEquals(CpuStrategy.DECK_PERCENTILE, CpuStrategy.fromConfig("deck-percentile"));
        assertEquals(CpuStrategy.OPPONENT_PERCENTILE, CpuStrategy.fromConfig("opponent-percentile"));
        assertEquals(CpuStrategy.SMART, CpuStrategy.fromConfig("smart"));
        assertEquals(CpuStrategy.OPPONENT_PERCENTILE, CpuStrategy.fromConfig("  OPPONENT_PERCENTILE "));
        assertThrows(IllegalArgumentException.class, () -> CpuStrategy.fromConfig("clever"));
        assertThrows(IllegalArgumentException.class, () -> CpuStrategy.fromConfig(null));
        for (CpuStrategy s : CpuStrategy.values()) assertEquals(s, CpuStrategy.fromConfig(s.configName()));
    }

    @Test
    void aProfileNeedsAStrategyAndNoiseBetweenZeroAndOne() {
        assertThrows(IllegalArgumentException.class, () -> CpuProfile.of(null, 0));
        assertThrows(IllegalArgumentException.class, () -> CpuProfile.of(CpuStrategy.SMART, -0.1));
        assertThrows(IllegalArgumentException.class, () -> CpuProfile.of(CpuStrategy.SMART, 1.1));
        assertThrows(IllegalArgumentException.class, () -> CpuProfile.of(CpuStrategy.SMART, Double.NaN));
        CpuProfile.of(CpuStrategy.SMART, 0);
        CpuProfile.of(CpuStrategy.SMART, 1);
    }

    @Test
    void theDefaultDifficultiesAreRandomOpponentPercentileAndSmart() {
        CpuProfiles d = CpuProfiles.defaults();
        assertEquals(CpuProfile.of(CpuStrategy.RANDOM, 0), d.profile(DifficultyLevel.easy));
        assertEquals(CpuProfile.of(CpuStrategy.OPPONENT_PERCENTILE, 0.25), d.profile(DifficultyLevel.medium));
        assertEquals(CpuProfile.of(CpuStrategy.SMART, 0), d.profile(DifficultyLevel.hard));
        assertFalse(d.toString().isEmpty());
    }
}
