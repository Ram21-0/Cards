package com.cards.live;

import com.cards.TestClock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A scheduler driven by the test: nothing runs until the fake clock has passed a task's time and {@link #runDue} is called. */
final class ManualGameScheduler implements GameScheduler {

    private static final class Task {
        final Instant at;
        final Runnable run;
        boolean cancelled;

        Task(Instant at, Runnable run) {
            this.at = at;
            this.run = run;
        }
    }

    private final TestClock clock;
    private final List<Task> tasks = new ArrayList<>();

    ManualGameScheduler(TestClock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Cancellable schedule(Instant at, Runnable task) {
        Task t = new Task(at, task);
        tasks.add(t);
        return () -> {
            synchronized (ManualGameScheduler.this) {
                t.cancelled = true;
            }
        };
    }

    /** Moves the clock forward and runs everything that has become due (including what that schedules in turn). */
    void advance(Duration d) {
        clock.advance(d);
        runDue();
    }

    int runDue() {
        int ran = 0;
        while (true) {
            Task next;
            synchronized (this) {
                tasks.removeIf(t -> t.cancelled);
                next = tasks.stream().filter(t -> !t.at.isAfter(clock.instant())).min(java.util.Comparator.comparing(t -> t.at)).orElse(null);
                if (next == null) return ran;
                tasks.remove(next);
            }
            next.run.run();
            ran++;
        }
    }

    synchronized Optional<Instant> nextAt() {
        return tasks.stream().filter(t -> !t.cancelled).map(t -> t.at).min(Instant::compareTo);
    }
}
