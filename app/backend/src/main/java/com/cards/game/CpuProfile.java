package com.cards.game;

/**
 * How one difficulty plays: a {@link CpuStrategy} and how much random noise is mixed into its judgement
 * (0 = always the best option, 1 = the noise is as large as the whole value range).
 */
public record CpuProfile(CpuStrategy strategy, double noise) {

    public CpuProfile {
        if (strategy == null) throw new IllegalArgumentException("A CPU profile needs a strategy");
        if (Double.isNaN(noise) || noise < 0 || noise > 1) throw new IllegalArgumentException("CPU noise must be between 0 and 1");
    }

    public static CpuProfile of(CpuStrategy strategy, double noise) {
        return new CpuProfile(strategy, noise);
    }
}
