package com.cards.live;

import com.cards.game.GameEngine;
import com.cards.game.GameEvent;
import com.cards.game.Phase;
import com.cards.service.db.GameRepository;
import com.cards.service.service.GameDetails;
import com.cards.service.service.GameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the running games: applies players' commands, wakes the engine at its deadlines (clash race, turn timeouts, CPU
 * calls), treats a human who is disconnected on their turn as a timeout after a grace period, tells everyone what happened
 * and saves the result when a game ends.
 *
 * <p>Every method that touches a game does so inside {@link LiveGame#withLock}, including the sending, so messages go
 * out in the order things happened.
 */
@Component
public class LivePlay {

    private static final Logger log = LoggerFactory.getLogger(LivePlay.class);

    private final GameRegistry registry;
    private final GameHub hub;
    private final LiveMessages messages;
    private final GameScheduler scheduler;
    private final GameRepository games;
    private final GameService gameService;
    private final Clock clock;
    private final Duration absenceGrace;

    public LivePlay(GameRegistry registry, GameHub hub, LiveMessages messages, GameScheduler scheduler, GameRepository games,
                    GameService gameService, Clock clock, @Value("${app.live.absence-grace-seconds:15}") int absenceGraceSeconds) {
        this.registry = registry;
        this.hub = hub;
        this.messages = messages;
        this.scheduler = scheduler;
        this.games = games;
        this.gameService = gameService;
        this.clock = clock;
        this.absenceGrace = Duration.ofSeconds(absenceGraceSeconds);
    }

    // ------------------------------------------------------------------------------------------ commands

    /** @return the caller's view after the command */
    public ViewMessage call(LiveGame live, int seat, String stat) {
        return live.withLock(() -> {
            afterChange(live, live.engine().call(seat, stat));
            return messages.viewMessage(live, seat);
        });
    }

    public ViewMessage choose(LiveGame live, int seat, int cardId) {
        return live.withLock(() -> {
            afterChange(live, live.engine().choose(seat, cardId));
            return messages.viewMessage(live, seat);
        });
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    /** The game has just started: tell everyone ({@code started}, the opening events, their views) and start the clocks. */
    public void begin(LiveGame live, GameDetails details) {
        live.withLock(() -> {
            Instant now = clock.instant();
            for (var seat : live.seats()) {
                if (!seat.isCpu() && !hub.isConnected(live.code(), seat.playerId())) live.absentSince().put(seat.seat(), now);
            }
            for (Connection c : hub.connections(live.code())) c.send(messages.started(details, c.player().id()));
            afterChange(live, live.engine().pollEvents());
            return null;
        });
    }

    /** A socket of this player opened. */
    public void connected(String code, UUID playerId) {
        registry.find(code).ifPresent(live -> live.withLock(() -> {
            Integer seat = live.seatOf(playerId);
            if (seat != null && live.absentSince().remove(seat) != null) reschedule(live);
            return null;
        }));
    }

    /** A socket of this player closed: when it was their last one they are absent from now on. */
    public void disconnected(String code, UUID playerId) {
        registry.find(code).ifPresent(live -> live.withLock(() -> {
            Integer seat = live.seatOf(playerId);
            if (seat != null && !hub.isConnected(code, playerId)) {
                live.absentSince().putIfAbsent(seat, clock.instant());
                reschedule(live);
            }
            return null;
        }));
    }

    /** The timer fired: apply everything that is due. */
    void wake(LiveGame live) {
        live.withLock(() -> {
            if (registry.find(live.code()).orElse(null) != live) return null;      // finished or replaced meanwhile
            GameEngine engine = live.engine();
            Instant now = clock.instant();
            List<GameEvent> events = new ArrayList<>(engine.tick(now));
            for (int seat : graceDueSeats(live, now)) events.addAll(engine.timeoutSeat(seat));
            afterChange(live, events);
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------ after every change

    /** Lock held. Sends events then views, saves the game if it ended, otherwise sets the next timer. */
    private void afterChange(LiveGame live, List<GameEvent> events) {
        List<Connection> connections = hub.connections(live.code());
        if (!events.isEmpty()) {
            String text = messages.events(live, events);
            for (Connection c : connections) c.send(text);
        }
        for (Connection c : connections) {
            Integer seat = live.seatOf(c.player().id());
            if (seat != null) c.send(messages.view(live, seat));
        }
        if (live.engine().isOver()) {
            finish(live, events, connections);
        } else {
            trackActing(live);
            reschedule(live);
        }
    }

    private void finish(LiveGame live, List<GameEvent> events, List<Connection> connections) {
        live.wakeup(null);
        GameEngine engine = live.engine();
        Map<Integer, Integer> placements = engine.placements();
        boolean byCap = events.stream().anyMatch(e -> e instanceof GameEvent.GameOver g && g.endedByRoundCap());
        try {
            games.finish(live.gameId(), placements, engine.round(), clock.instant());
        } catch (RuntimeException e) {
            log.error("Could not save the result of game {}", live.code(), e);
        }
        registry.remove(live.code());
        GameDetails details = gameService.get(live.code());
        for (Connection c : connections) c.send(messages.gameOver(details, c.player().id(), placements, byCap));
    }

    /** Who may act now and since when: a new phase restarts the grace clock of everyone in it. */
    private void trackActing(LiveGame live) {
        GameEngine engine = live.engine();
        var view = engine.view(1);
        String key = view.phase() + ":" + view.round() + ":" + view.clashLevel();
        var acting = engine.actingSeats();
        Instant now = clock.instant();
        if (!key.equals(live.stateKey())) {
            live.stateKey(key);
            live.actingSince().clear();
        }
        live.actingSince().keySet().retainAll(acting);
        for (int seat : acting) live.actingSince().putIfAbsent(seat, now);
    }

    /** Human seats that are absent, may act in a phase with a timeout, and have been for longer than the grace. */
    private List<Integer> graceDueSeats(LiveGame live, Instant now) {
        List<Integer> due = new ArrayList<>();
        for (int seat : graceCandidates(live)) {
            if (!graceEnd(live, seat).isAfter(now)) due.add(seat);
        }
        return due;
    }

    private List<Integer> graceCandidates(LiveGame live) {
        if (live.engine().isOver() || live.engine().phase() == Phase.CLASH_AWAIT_CALL) return List.of();
        return live.engine().actingSeats().stream()
                .filter(seat -> live.isHuman(seat) && live.absentSince().containsKey(seat)).toList();
    }

    private Instant graceEnd(LiveGame live, int seat) {
        Instant absent = live.absentSince().get(seat);
        Instant acting = live.actingSince().getOrDefault(seat, absent);
        return (absent.isAfter(acting) ? absent : acting).plus(absenceGrace);
    }

    /** Lock held. One timer per game: at the earliest of the engine's wake-up and the next grace period ending. */
    private void reschedule(LiveGame live) {
        Optional<Instant> next = live.engine().nextWakeup();
        for (int seat : graceCandidates(live)) {
            Instant end = graceEnd(live, seat);
            if (next.isEmpty() || end.isBefore(next.get())) next = Optional.of(end);
        }
        live.wakeup(next.map(at -> scheduler.schedule(at, () -> wake(live))).orElse(null));
    }
}
