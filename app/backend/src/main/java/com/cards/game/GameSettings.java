package com.cards.game;

/**
 * Per-game settings (RULES.md §1). The defaults come from application.yml; the host may override them when creating
 * the game. Every value is range-checked here, so a {@code GameSettings} that exists is always valid.
 *
 * @param maxRounds          safety cap, because cards cycle (20–5000)
 * @param chooseThreshold    a player with 2…this many cards may CHOOSE; 0 (or 1) disables CHOOSE (0–10)
 * @param clashCallSeconds   time to call a stat in a clash (2–30)
 * @param turnTimeoutSeconds time for a normal call / choose (5–120); null = no limit
 */
public record GameSettings(int maxRounds, int chooseThreshold, int clashCallSeconds, Integer turnTimeoutSeconds) {

    public static final int DEFAULT_MAX_ROUNDS = 500;
    public static final int DEFAULT_CHOOSE_THRESHOLD = 3;
    public static final int DEFAULT_CLASH_CALL_SECONDS = 5;

    public static final int MIN_MAX_ROUNDS = 20;
    public static final int MAX_MAX_ROUNDS = 5000;
    public static final int MIN_CHOOSE_THRESHOLD = 0;
    public static final int MAX_CHOOSE_THRESHOLD = 10;
    public static final int MIN_CLASH_CALL_SECONDS = 2;
    public static final int MAX_CLASH_CALL_SECONDS = 30;
    public static final int MIN_TURN_TIMEOUT_SECONDS = 5;
    public static final int MAX_TURN_TIMEOUT_SECONDS = 120;

    /** @throws IllegalArgumentException with a message fit for an API error, naming the offending setting */
    public GameSettings {
        requireRange("maxRounds", maxRounds, MIN_MAX_ROUNDS, MAX_MAX_ROUNDS);
        requireRange("chooseThreshold", chooseThreshold, MIN_CHOOSE_THRESHOLD, MAX_CHOOSE_THRESHOLD);
        requireRange("clashCallSeconds", clashCallSeconds, MIN_CLASH_CALL_SECONDS, MAX_CLASH_CALL_SECONDS);
        if (turnTimeoutSeconds != null) {
            requireRange("turnTimeoutSeconds", turnTimeoutSeconds, MIN_TURN_TIMEOUT_SECONDS, MAX_TURN_TIMEOUT_SECONDS);
        }
    }

    public static GameSettings defaults() {
        return new GameSettings(DEFAULT_MAX_ROUNDS, DEFAULT_CHOOSE_THRESHOLD, DEFAULT_CLASH_CALL_SECONDS, null);
    }

    /** The host's overrides on top of {@code defaults}: a null override keeps the default (turnTimeoutSeconds: null = the default, which may itself be "none"). */
    public static GameSettings resolve(Integer maxRounds, Integer chooseThreshold, Integer clashCallSeconds,
                                       Integer turnTimeoutSeconds, GameSettings defaults) {
        return new GameSettings(
                maxRounds != null ? maxRounds : defaults.maxRounds(),
                chooseThreshold != null ? chooseThreshold : defaults.chooseThreshold(),
                clashCallSeconds != null ? clashCallSeconds : defaults.clashCallSeconds(),
                turnTimeoutSeconds != null ? turnTimeoutSeconds : defaults.turnTimeoutSeconds());
    }

    /** True when a player holding {@code handSize} cards may CHOOSE (2 ≤ cards ≤ threshold). */
    public boolean mayChoose(int handSize) {
        return handSize >= 2 && handSize <= chooseThreshold;
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }
}
