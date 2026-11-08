package com.cards.live;

import com.cards.common.Player;
import com.cards.service.service.GameDetails;
import com.cards.service.service.GameService;
import com.cards.service.service.StartedGame;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ties the database side of a game (lobby actions, in {@link GameService}) to the live side (the running engine and
 * the open WebSockets): after each change it tells everyone connected.
 */
@Service
public class LiveGameService {

    private final GameService games;
    private final GameHub hub;
    private final GameRegistry registry;
    private final LiveMessages messages;
    /** One lock per game code so lobby pushes cannot overtake each other; each push reads the latest state. */
    private final Map<String, Object> pushLocks = new ConcurrentHashMap<>();

    public LiveGameService(GameService games, GameHub hub, GameRegistry registry, LiveMessages messages) {
        this.games = games;
        this.hub = hub;
        this.registry = registry;
        this.messages = messages;
    }

    // ------------------------------------------------------------------------------------------ lobby actions

    public GameDetails join(String code, Player me) {
        GameDetails details = games.join(code, me);
        pushLobby(details.game().joinCode());
        return details;
    }

    public GameDetails addCpu(String code, Player host, String difficulty) {
        GameDetails details = games.addCpu(code, host, difficulty);
        pushLobby(details.game().joinCode());
        return details;
    }

    public GameDetails removeSeat(String code, Player host, int seat) {
        GameDetails details = games.removeSeat(code, host, seat);
        pushLobby(details.game().joinCode());
        return details;
    }

    /** A human leaves the lobby: their sockets are closed, everyone else sees the new seats. */
    public GameDetails leave(String code, Player me) {
        GameDetails details = games.leave(code, me);
        String joinCode = details.game().joinCode();
        hub.closePlayer(joinCode, me.id(), CloseStatus.NORMAL);
        pushLobby(joinCode);
        return details;
    }

    /** Starts the game: creates the engine, registers the running game and tells everyone ({@code started}, then their {@code view}). */
    public GameDetails start(String code, Player host) {
        StartedGame started = games.start(code, host);
        GameDetails details = started.details();
        String joinCode = details.game().joinCode();
        LiveGame live = new LiveGame(joinCode, details.game().id(), started.engine(), details.deck(), details.seats());
        registry.put(live);
        synchronized (lockFor(joinCode)) {
            for (Connection c : hub.connections(joinCode)) {
                c.send(messages.started(details, c.player().id()));
                sendView(live, c);
            }
        }
        return details;
    }

    // ------------------------------------------------------------------------------------------ connections

    /** A socket just opened: bring it up to date (the game as it is now; the personal view when the game is running). */
    public void onConnect(Connection c) {
        synchronized (lockFor(c.code())) {
            GameDetails details = games.get(c.code());
            c.send(messages.lobby(details, c.player().id()));
            registry.find(c.code()).ifPresent(live -> sendView(live, c));
        }
    }

    public void onDisconnect(Connection c) {
        // stage 2: an absent human on their turn times out after a grace period
    }

    // ------------------------------------------------------------------------------------------ pushes

    /** Everyone in the lobby gets the game as it is now. */
    void pushLobby(String code) {
        synchronized (lockFor(code)) {
            if (hub.connections(code).isEmpty()) return;
            GameDetails details = games.get(code);
            for (Connection c : hub.connections(code)) c.send(messages.lobby(details, c.player().id()));
        }
    }

    private void sendView(LiveGame live, Connection c) {
        Integer seat = live.seatOf(c.player().id());
        if (seat != null) c.send(messages.view(live, seat));
    }

    Optional<LiveGame> running(String code) {
        return registry.find(code);
    }

    private Object lockFor(String code) {
        return pushLocks.computeIfAbsent(code, k -> new Object());
    }
}
