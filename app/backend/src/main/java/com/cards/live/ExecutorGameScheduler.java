package com.cards.live;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** The real scheduler: a small daemon thread pool; the delay is measured on the injected clock. */
@Component
public class ExecutorGameScheduler implements GameScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExecutorGameScheduler.class);

    private final Clock clock;
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread t = new Thread(runnable, "game-timer");
        t.setDaemon(true);
        return t;
    });

    public ExecutorGameScheduler(Clock clock) {
        this.clock = clock;
        executor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public Cancellable schedule(Instant at, Runnable task) {
        long delayMs = Math.max(0, Duration.between(clock.instant(), at).toMillis());
        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("A game timer failed", e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
