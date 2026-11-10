package com.cards.live;

import com.cards.common.Deck;
import com.cards.common.SeatInfo;
import com.cards.game.GameEngine;
import com.cards.game.PlayerView;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A running game: the rules engine plus who sits where. The engine is not thread-safe, so ALL access to it (and to the
 * bookkeeping below) goes through {@link #withLock} (one lock per game). Messages for a game are sent while holding the lock, so
 * everyone receives them in the order things happened.
 */
public final class LiveGame {

    private final String code;
    private final UUID gameId;
    private final GameEngine engine;
    private final Deck deck;
    private final List<SeatInfo> seats;                 // seat numbers are 1..n, the same as the engine's
    private final ReentrantLock lock = new ReentrantLock();

    // ---- bookkeeping, guarded by the lock
    private final Map<Integer, Instant> absentSince = new HashMap<>();      // human seats with no open socket
    private final Map<Integer, Instant> actingSince = new HashMap<>();      // seats that may act now, since when
    private String stateKey = "";
    private GameScheduler.Cancellable wakeup;

    public LiveGame(String code, UUID gameId, GameEngine engine, Deck deck, List<SeatInfo> seats) {
        this.code = code;
        this.gameId = gameId;
        this.engine = engine;
        this.deck = deck;
        this.seats = List.copyOf(seats);
    }

    public String code() {
        return code;
    }

    public UUID gameId() {
        return gameId;
    }

    public Deck deck() {
        return deck;
    }

    public List<SeatInfo> seats() {
        return seats;
    }

    /** The engine; only touch it inside {@link #withLock}. */
    GameEngine engine() {
        return engine;
    }

    Map<Integer, Instant> absentSince() {
        return absentSince;
    }

    Map<Integer, Instant> actingSince() {
        return actingSince;
    }

    String stateKey() {
        return stateKey;
    }

    void stateKey(String key) {
        this.stateKey = key;
    }

    void wakeup(GameScheduler.Cancellable next) {
        if (wakeup != null) wakeup.cancel();
        wakeup = next;
    }

    /** Runs {@code action} with exclusive access to the engine. */
    public <T> T withLock(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** What one seat may know right now. */
    public PlayerView view(int seat) {
        return withLock(() -> engine.view(seat));
    }

    public Map<Integer, Integer> placements() {
        return withLock(engine::placements);
    }

    /** The seat of a human player, or null when they are not in this game. */
    public Integer seatOf(UUID playerId) {
        return seats.stream().filter(s -> playerId.equals(s.playerId())).map(SeatInfo::seat).findFirst().orElse(null);
    }

    boolean isHuman(int seat) {
        return seats.stream().anyMatch(s -> s.seat() == seat && !s.isCpu());
    }
}
