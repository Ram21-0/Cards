package com.cards.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The open WebSocket connections, by game code. Also keeps them alive with pings and drops the ones that went quiet. */
@Component
public class GameHub {

    private final Clock clock;
    private final Duration staleAfter;
    private final Map<String, Set<Connection>> byGame = new ConcurrentHashMap<>();

    public GameHub(Clock clock, @Value("${app.live.stale-seconds:90}") int staleSeconds) {
        this.clock = clock;
        this.staleAfter = Duration.ofSeconds(staleSeconds);
    }

    public void add(Connection connection) {
        byGame.computeIfAbsent(connection.code(), c -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    public void remove(Connection connection) {
        byGame.computeIfPresent(connection.code(), (code, set) -> {
            set.remove(connection);
            return set.isEmpty() ? null : set;
        });
    }

    /** A snapshot of the game's open connections. */
    public List<Connection> connections(String code) {
        Set<Connection> set = byGame.get(code);
        return set == null ? List.of() : new ArrayList<>(set);
    }

    /** Is this player connected to the game with at least one open socket? */
    public boolean isConnected(String code, UUID playerId) {
        return connections(code).stream().anyMatch(c -> c.isOpen() && c.player().id().equals(playerId));
    }

    public void closePlayer(String code, UUID playerId, CloseStatus status) {
        connections(code).stream().filter(c -> c.player().id().equals(playerId)).forEach(c -> c.close(status));
    }

    /** Keeps connections alive (proxies drop idle ones) and closes the ones that have not answered for a while. */
    @Scheduled(fixedDelayString = "${app.live.ping-millis:25000}")
    public void pingAll() {
        Instant now = clock.instant();
        for (Set<Connection> set : byGame.values()) {
            for (Connection c : new ArrayList<>(set)) {
                if (Duration.between(c.lastSeen(), now).compareTo(staleAfter) > 0) c.close(CloseStatus.GOING_AWAY);
                else c.ping();
            }
        }
    }
}
