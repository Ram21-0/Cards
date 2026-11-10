package com.cards.config;

import com.cards.game.CpuProfile;
import com.cards.game.CpuProfiles;
import com.cards.game.CpuStrategy;
import com.cards.game.GameSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Game defaults from application.yml ({@code app.game.*}). Invalid values (a range outside RULES.md §1, an unknown CPU
 * strategy, noise outside 0..1) stop the application at startup rather than surfacing during a game.
 */
@Configuration
public class GameConfig {

    /** The settings a new game gets unless the host overrides them. */
    @Bean
    public GameSettings defaultGameSettings(
            @Value("${app.game.max-rounds:500}") int maxRounds,
            @Value("${app.game.choose-threshold:3}") int chooseThreshold,
            @Value("${app.game.clash-call-seconds:5}") int clashCallSeconds,
            @Value("${app.game.turn-timeout-seconds:}") Integer turnTimeoutSeconds) {
        return new GameSettings(maxRounds, chooseThreshold, clashCallSeconds, turnTimeoutSeconds);
    }

    /** Which strategy (and how much noise) plays each CPU difficulty. */
    @Bean
    public CpuProfiles cpuProfiles(
            @Value("${app.game.cpu.easy.strategy:random}") String easyStrategy,
            @Value("${app.game.cpu.easy.noise:0}") double easyNoise,
            @Value("${app.game.cpu.medium.strategy:opponent-percentile}") String mediumStrategy,
            @Value("${app.game.cpu.medium.noise:0.25}") double mediumNoise,
            @Value("${app.game.cpu.hard.strategy:smart}") String hardStrategy,
            @Value("${app.game.cpu.hard.noise:0}") double hardNoise,
            @Value("${app.game.cpu.extreme.strategy:counting}") String extremeStrategy,
            @Value("${app.game.cpu.extreme.noise:0}") double extremeNoise) {
        return CpuProfiles.of(
                CpuProfile.of(CpuStrategy.fromConfig(easyStrategy), easyNoise),
                CpuProfile.of(CpuStrategy.fromConfig(mediumStrategy), mediumNoise),
                CpuProfile.of(CpuStrategy.fromConfig(hardStrategy), hardNoise),
                CpuProfile.of(CpuStrategy.fromConfig(extremeStrategy), extremeNoise));
    }
}
