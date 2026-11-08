package com.cards.game;

import com.cards.common.DifficultyLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * The strategy of each difficulty. Defaults (also in application.yml, where they can be changed):
 * easy = random, medium = opponent-percentile with a little noise, hard = smart.
 */
public final class CpuProfiles {

    private final Map<DifficultyLevel, CpuProfile> byLevel;

    private CpuProfiles(Map<DifficultyLevel, CpuProfile> byLevel) {
        this.byLevel = byLevel;
    }

    public static CpuProfiles of(CpuProfile easy, CpuProfile medium, CpuProfile hard) {
        Map<DifficultyLevel, CpuProfile> map = new EnumMap<>(DifficultyLevel.class);
        map.put(DifficultyLevel.easy, easy);
        map.put(DifficultyLevel.medium, medium);
        map.put(DifficultyLevel.hard, hard);
        return new CpuProfiles(map);
    }

    public static CpuProfiles defaults() {
        return of(CpuProfile.of(CpuStrategy.RANDOM, 0),
                CpuProfile.of(CpuStrategy.OPPONENT_PERCENTILE, 0.25),
                CpuProfile.of(CpuStrategy.SMART, 0));
    }

    public CpuProfile profile(DifficultyLevel level) {
        return byLevel.get(level);
    }

    @Override
    public String toString() {
        return byLevel.toString();
    }
}
