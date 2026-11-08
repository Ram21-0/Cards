package com.cards.game;

import com.cards.common.Card;
import com.cards.common.DifficultyLevel;
import com.cards.common.Outcome;
import com.cards.common.StatDefinition;

import java.util.ArrayList;
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
     * @param opponentPools for the counting strategy: for each opponent in the round, the cards their next card can be
     *                  (empty = no card counting: every card the CPU does not hold is a candidate for everyone)
     */
    record Situation(List<Card> ownCards, int opponents, int potSize, List<List<Card>> opponentPools) {
        Situation(List<Card> ownCards, int opponents, int potSize) {
            this(ownCards, opponents, potSize, List.of());
        }
    }

    /** Does this difficulty count cards (so the engine should work out what is known)? */
    boolean countsCards(DifficultyLevel level) {
        return profiles.profile(level).strategy() == CpuStrategy.COUNTING;
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
        if (profile.strategy() != CpuStrategy.SMART && profile.strategy() != CpuStrategy.COUNTING && allHopeless(hand, statIndex, own)) {
            return rnd.nextInt(hand.size());                  // nothing can win: the percentile strategies have no sacrifice logic, so no card is "least bad"
        }
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

    /** True when no card in the hand can even tie any card the CPU does not hold on this stat. */
    private boolean allHopeless(List<Card> hand, int statIndex, Set<Integer> own) {
        for (Card card : hand) {
            if (pool(card, statIndex, own).beatenOrTied() > 0) return false;
        }
        return true;
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
            case SMART, COUNTING -> expectedValue(profile.strategy(), card, statIndex, sit, own);
        };
    }

    /**
     * p × prize − (1 − p) × cost. p: the chance this card beats every opponent (a tie counts half). prize: the cards
     * won net (one per opponent, plus the pot). cost: the card lost, 1 plus how much a keeper it is (its best stat's
     * percentile among the cards the CPU does not hold).
     */
    private double expectedValue(CpuStrategy strategy, Card card, int statIndex, Situation sit, Set<Integer> own) {
        int n = Math.max(1, sit.opponents());
        List<Pool> pools = new ArrayList<>();
        if (strategy == CpuStrategy.COUNTING && !sit.opponentPools().isEmpty()) {
            for (List<Card> candidates : sit.opponentPools()) pools.add(pool(card, statIndex, candidates));
        } else {
            Pool shared = pool(card, statIndex, own);
            for (int i = 0; i < n; i++) pools.add(shared);
        }
        double prize = n + sit.potSize();
        double cost = 1 + keepValue(card, own);
        double pTie = 1, pWin = 1;
        boolean cannotWin = true;
        for (Pool pool : pools) {
            pTie *= pool.equal();
            pWin *= pool.better() + 0.5 * pool.equal();
            if (pool.better() > 0) cannotWin = false;
        }
        if (cannotWin && pTie > 0) return hopefulTieValue(card, sit, own, pTie, 1 - pBeatOrTie(pools), prize, cost);
        return pWin * prize - (1 - pWin) * cost;
    }

    /** P(not beaten by anyone and tied with all): the chance that neither wins nor loses outright. */
    private static double pBeatOrTie(List<Pool> pools) {
        double p = 1;
        for (Pool pool : pools) p *= pool.better() + pool.equal();
        return p;
    }

    /**
     * The card cannot beat anything on this stat but can tie, and a tie means a clash. The tie is therefore not "half a
     * win": it is worth the chance of winning the clash, which depends on how strong the CPU's OTHER cards are overall
     * (the clash is played with a later card and a stat the CPU may pick, so one stat tells nothing). Strong cards
     * behind this one make the tie valuable, so a strong card is not given away; weak ones make it a losing bet.
     * value = P(tie) × (clashWin × clashPrize − (1 − clashWin) × clashCost) − P(lose outright) × cost
     */
    private double hopefulTieValue(Card card, Situation sit, Set<Integer> own, double pTie, double pLose, double prize, double cost) {
        double clashWin = overallStrength(card, sit, own);
        double clashPrize = prize + 1;                                      // the clash cards are added to the pot
        double clashCost = cost + 1;                                        // a lost clash costs this card and the next
        return pTie * (clashWin * clashPrize - (1 - clashWin) * clashCost) - pLose * cost;
    }

    /** 0..1: the average over the CPU's other cards of their best stat (beating or tying the cards it does not hold). */
    private double overallStrength(Card exclude, Situation sit, Set<Integer> own) {
        double sum = 0;
        int count = 0;
        for (Card other : sit.ownCards()) {
            if (other.id() == exclude.id()) continue;
            double best = 0;
            for (int s = 0; s < stats.size(); s++) best = Math.max(best, pool(other, s, own).beatenOrTied());
            sum += best;
            count++;
        }
        return count == 0 ? 0 : sum / count;
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
        double range = (profile.strategy() == CpuStrategy.SMART || profile.strategy() == CpuStrategy.COUNTING)
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
        List<Card> unheld = new ArrayList<>();
        for (Card other : deck) if (!own.contains(other.id())) unheld.add(other);
        return pool(card, statIndex, unheld);
    }

    /** The same comparison against an explicit list of candidate cards (what one opponent may play). */
    private Pool pool(Card card, int statIndex, List<Card> candidates) {
        StatDefinition stat = stats.get(statIndex);
        int better = 0, equal = 0;
        for (Card other : candidates) {
            Outcome outcome = stat.compare(card.stat(stat.key()), other.stat(stat.key()));
            if (outcome == Outcome.WIN) better++;
            else if (outcome == Outcome.TIE) equal++;
        }
        int size = candidates.size();
        return size == 0 ? new Pool(0.5, 0) : new Pool(better / (double) size, equal / (double) size);
    }
}
