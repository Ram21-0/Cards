package com.cards.live;

import com.cards.service.db.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Running games live in memory only, so after a restart every game still marked IN_PROGRESS has lost its state and can
 * never be finished: mark them ABANDONED.
 */
@Component
public class StartupCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupCleanup.class);

    private final GameRepository games;
    private final Clock clock;

    public StartupCleanup(GameRepository games, Clock clock) {
        this.games = games;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        int abandoned = games.abandonInProgress(clock.instant());
        if (abandoned > 0) log.warn("Marked {} game(s) that were in progress before the restart as ABANDONED", abandoned);
    }
}
