package com.cards.live;

import com.cards.common.Card;
import com.cards.common.StatDefinition;
import com.cards.game.CpuProfiles;
import com.cards.game.GameEngine;
import com.cards.game.GameEngineFactory;
import com.cards.game.GameSettings;
import com.cards.game.SeatSpec;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.SplittableRandom;

/** The real thing: shuffles with a fresh random seed and uses the configured CPU strategies and the shared clock. */
@Component
public class RandomGameEngineFactory implements GameEngineFactory {

    private final CpuProfiles cpuProfiles;
    private final Clock clock;
    private final SecureRandom seeds = new SecureRandom();

    public RandomGameEngineFactory(CpuProfiles cpuProfiles, Clock clock) {
        this.cpuProfiles = cpuProfiles;
        this.clock = clock;
    }

    @Override
    public GameEngine create(List<Card> deck, List<StatDefinition> stats, List<SeatSpec> seats, GameSettings settings) {
        return GameEngine.start(deck, stats, seats, settings, cpuProfiles, new SplittableRandom(seeds.nextLong()), clock);
    }
}
