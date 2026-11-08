package com.cards.game;

import java.util.Locale;

/**
 * How a CPU judges its options (RULES.md §7). Which strategy plays which difficulty is configuration
 * ({@code app.game.cpu.*} in application.yml, see {@link CpuProfiles}).
 *
 * <p>All of them are fair: they use only what a player could know — the CPU's own hand, the deck (every card of the
 * deck and its stats are public), how many opponents are in the round, and the size of the pot.
 */
public enum CpuStrategy {

    /** No thinking: a random stat, a random card. */
    RANDOM("random"),

    /** The share of the WHOLE deck the card beats on the stat (its percentile among all cards). */
    DECK_PERCENTILE("deck-percentile"),

    /**
     * The share of only the cards the CPU does NOT hold that the card beats. The CPU's own cards cannot be its
     * opponents' cards, so counting them (as the deck percentile does) distorts the estimate.
     */
    OPPONENT_PERCENTILE("opponent-percentile"),

    /**
     * Expected value. The chance of winning is estimated against the cards the CPU does not hold (a tie counts half),
     * raised to the number of opponents still in the round, since the card must beat every one of them. The value is
     * {@code p × (cards won) − (1 − p) × (cost of losing this card)}: a hopeless round is "lost anyway", so the weakest
     * card is sacrificed and the strong ones are kept; a safe round is played with the card that wins it.
     */
    SMART("smart");

    private final String configName;

    CpuStrategy(String configName) {
        this.configName = configName;
    }

    /** The name used in application.yml. */
    public String configName() {
        return configName;
    }

    /** Parses the application.yml name ("deck-percentile", "DECK_PERCENTILE", …). */
    public static CpuStrategy fromConfig(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (CpuStrategy s : values()) {
            if (s.configName.equals(normalized)) return s;
        }
        throw new IllegalArgumentException("Unknown CPU strategy '" + name + "' (use random, deck-percentile, opponent-percentile or smart)");
    }
}
