package com.cards.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** One clock for everything time-based (sessions, nonces) so tests can control time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
