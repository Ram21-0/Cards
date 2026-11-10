package com.cards.live;

import java.time.Instant;

/** Runs a task at a point in time (by the injected clock). Tests use a manual one, so no test ever sleeps. */
public interface GameScheduler {

    Cancellable schedule(Instant at, Runnable task);

    interface Cancellable {
        void cancel();
    }
}
