package com.cards.live;

import com.cards.common.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A modest per-player limit on the game action endpoints (default: 30 requests per 10 seconds) — far above what a person
 * clicking can reach, low enough to stop a script from hammering the server. A fixed window per player; in memory.
 */
@Component
public class RateLimiter {

    private record Window(Instant start, int count) {
    }

    private static final int PURGE_ABOVE = 10_000;

    private final Clock clock;
    private final int limit;
    private final Duration window;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock,
                       @Value("${app.rate-limit.actions:30}") int limit,
                       @Value("${app.rate-limit.window-seconds:10}") int windowSeconds) {
        this.clock = clock;
        this.limit = limit;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /** Counts one action by this player. @throws TooManyRequestsException when the player is over the limit */
    public void check(UUID playerId) {
        Instant now = clock.instant();
        if (windows.size() > PURGE_ABOVE) windows.values().removeIf(w -> !now.isBefore(w.start().plus(window)));
        Window w = windows.compute(playerId, (id, old) ->
                old == null || !now.isBefore(old.start().plus(window)) ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
        if (w.count() > limit) throw new TooManyRequestsException("Too many requests, slow down");
    }
}
