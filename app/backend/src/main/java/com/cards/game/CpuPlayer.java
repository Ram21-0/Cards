package com.cards.game;

import com.cards.common.Card;
import com.cards.common.DifficultyLevel;
import com.cards.common.Outcome;
import com.cards.common.StatDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The decisions of CPU seats (RULES.md §7). What a difficulty does is its {@link CpuProfile}: a {@link CpuStrategy}
 * that scores the options, plus noise. All randomness comes from the injected generator, so games replay exactly.
 */
final class CpuPlayer {

    static final long CLASH_DELAY_MIN_MILLIS = 1500;
    static final long CLASH_DELAY_MAX_MILLIS = 4000;

    /**
     * What the CPU may know when deciding.
     *
     * @param ownCards  every card the CPU holds, including one it already put in play this round
     * @param opponents how many other players are in this round / clash
     * @param potSize   cards waiting in the pot (a clash or carry), part of the prize
     */
    record Situation(List<Card> ownCards, int opponents, int potSize) {
    }

    private final List<StatDefinition> stats;
    private final List<Card> deck;
    private final CardStrengths deckStrengths;
    private final CpuProfiles profiles;
    private final RandomGenerator rnd;

    CpuPlayer(List<StatDefinition> stats, List<Card> deck, CpuProfiles profiles, RandomGenerator rnd) {
        this.stats = stats;
        this.deck = List.copyOf(deck);
        this.deckStrengths = new CardStrengths(deck, stats);
        this.profiles = profiles;
        this.rnd = rnd;
    }

    /** Which stat to call with {@code card}. Returns an index into the stats. */
    int pickStat(Card card, DifficultyLevel level, Situation sit) {
        CpuProfile profile = profiles.profile(level);
        if (profile.strategy() == CpuStrategy.RANDOM) return rnd.nextInt(stats.size());
        Set<Integer> own = ownIds(sit);
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int s = 0; s < stats.size(); s++) {
            double score = value(profile, card, s, sit, own) + noise(profile, sit);
            if (score > bestScore) {                              // strict: ties go to the earlier stat
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /** The caller's CHOOSE: the stat is not known yet, so judge each card by its strongest stat. Returns an index into {@code hand}. */
    int pickCardForCaller(List<Card> hand, DifficultyLevel level, Situation sit) {
        CpuProfile profile = profiles.profile(level);
        if (profile.strategy() == CpuStrategy.RANDOM) return rnd.nextInt(hand.size());
        Set<Integer> own = ownIds(sit);
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < hand.size(); i++) {
            double top = Double.NEGATIVE_INFINITY;
            for (int s = 0; s < stats.size(); s++) top = Math.max(top, value(profile, hand.get(i), s, sit, own));
            double score = top + noise(profile, sit);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Anyone else's CHOOSE: the stat is known. Returns an index into {@code hand}. */
    int pickCardForStat(List<Card> hand, int statIndex, DifficultyLevel level, Situation sit) {
        CpuProfile profile = profiles.profile(level);
        if (profile.strategy() == CpuStrategy.RANDOM) return rnd.nextInt(hand.size());
        Set<Integer> own = ownIds(sit);
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < hand.size(); i++) {
            double score = value(profile, hand.get(i), statIndex, sit, own) + noise(profile, sit);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** How long a CPU waits before calling in a clash, so humans can win the race: 1.5–4.0 s. */
    long clashDelayMillis() {
        return CLASH_DELAY_MIN_MILLIS + rnd.nextLong(CLASH_DELAY_MAX_MILLIS - CLASH_DELAY_MIN_MILLIS + 1);
    }

    // ------------------------------------------------------------------------------------------ scoring

    /** How good it is to play {@code card} on the stat (higher = better). The scale depends on the strategy. */
    double value(CpuProfile profile, Card card, int statIndex, Situation sit, Set<Integer> own) {
        return switch (profile.strategy()) {
            case RANDOM -> 0;
            case DECK_PERCENTILE -> deckStrengths.of(card, statIndex);
            case OPPONENT_PERCENTILE -> pool(card, statIndex, own).beaten();
            case SMART -> expectedValue(card, statIndex, sit, own);
        };
    }

    /**
     * p × prize − (1 − p) × cost. p: the chance this card beats every opponent (a tie counts half). prize: the cards
     * won net (one per opponent, plus the pot). cost: the card lost, 1 plus how much a keeper it is (its best stat's
     * percentile among the cards the CPU does not hold).
     */
    private double expectedValue(Card card, int statIndex, Situation sit, Set<Integer> own) {
        double beatOne = pool(card, statIndex, own).beatenOrTied();
        double p = Math.pow(beatOne, Math.max(1, sit.opponents()));
        double prize = Math.max(1, sit.opponents()) + sit.potSize();
        double cost = 1 + keepValue(card, own);
        return p * prize - (1 - p) * cost;
    }

    /** 0..1: how strong the card's best stat is among the cards the CPU does not hold — how much it wants to keep it. */
    private double keepValue(Card card, Set<Integer> own) {
        double best = 0;
        for (int s = 0; s < stats.size(); s++) best = Math.max(best, pool(card, s, own).beaten());
        return best;
    }

    /** Noise as a share of the strategy's value range, so the same setting means the same on every scale. */
    private double noise(CpuProfile profile, Situation sit) {
        if (profile.noise() == 0 || profile.strategy() == CpuStrategy.RANDOM) return 0;
        double range = profile.strategy() == CpuStrategy.SMART
                ? Math.max(1, sit.opponents()) + sit.potSize() + 2          // from -(1 + keep) up to the prize
                : 1;
        return rnd.nextDouble() * profile.noise() * range;
    }

    private static Set<Integer> ownIds(Situation sit) {
        Set<Integer> ids = new HashSet<>();
        for (Card c : sit.ownCards()) ids.add(c.id());
        return ids;
    }

    // ------------------------------------------------------------------------------------------ the pool

    /** How a card on a stat compares with the pool of cards the CPU does not hold, as shares of that pool. */
    private record Pool(double better, double equal) {
        double beaten() {
            return better;
        }

        double beatenOrTied() {
            return better + 0.5 * equal;
        }
    }

    /**
     * The cards the CPU does not hold are the only ones an opponent can play (or the pot can hold), so the card is
     * measured against them alone. An empty pool (the CPU holds everything) is neutral.
     */
    private Pool pool(Card card, int statIndex, Set<Integer> own) {
        StatDefinition stat = stats.get(statIndex);
        int better = 0, equal = 0, size = 0;
        for (Card other : deck) {
            if (own.contains(other.id())) continue;
            size++;
            Outcome outcome = stat.compare(card.stat(stat.key()), other.stat(stat.key()));
            if (outcome == Outcome.WIN) better++;
            else if (outcome == Outcome.TIE) equal++;
        }
        return size == 0 ? new Pool(0.5, 0) : new Pool(better / (double) size, equal / (double) size);
    }
}
