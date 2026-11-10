package com.cards.live;

import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.ForbiddenException;
import com.cards.common.Player;
import com.cards.game.GameRuleException;
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
    private final LivePlay play;
    /** One lock per game code so lobby pushes cannot overtake each other; each push reads the latest state. */
    private final Map<String, Object> pushLocks = new ConcurrentHashMap<>();

    public LiveGameService(GameService games, GameHub hub, GameRegistry registry, LiveMessages messages, LivePlay play) {
        this.games = games;
        this.hub = hub;
        this.registry = registry;
        this.messages = messages;
        this.play = play;
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
            play.begin(live, details);
        }
        return details;
    }

    // ------------------------------------------------------------------------------------------ playing

    /** The seat's owner calls a stat. Answers with their new view (everyone also gets it over the WebSocket). */
    public ViewMessage call(String code, Player me, String stat) {
        LiveGame live = runningGameOf(code, me);
        try {
            return play.call(live, live.seatOf(me.id()), stat);
        } catch (GameRuleException e) {
            throw refused(e);
        }
    }

    public ViewMessage choose(String code, Player me, int cardId) {
        LiveGame live = runningGameOf(code, me);
        try {
            return play.choose(live, live.seatOf(me.id()), cardId);
        } catch (GameRuleException e) {
            throw refused(e);
        }
    }

    private LiveGame runningGameOf(String code, Player me) {
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        games.get(normalized);                                              // 404 for an unknown game
        LiveGame live = registry.find(normalized).orElseThrow(() -> new ConflictException("The game is not in progress"));
        if (live.seatOf(me.id()) == null) throw new ForbiddenException("You are not in this game");
        return live;
    }

    private static RuntimeException refused(GameRuleException e) {
        return switch (e.kind()) {
            case BAD_INPUT -> new BadRequestException(e.getMessage());
            case NOT_YOUR_TURN -> new ForbiddenException(e.getMessage());
            case WRONG_PHASE, GAME_OVER -> new ConflictException(e.getMessage());
        };
    }

    // ------------------------------------------------------------------------------------------ connections

    /** A socket just opened: bring it up to date (the game as it is now; the personal view when the game is running). */
    public void onConnect(Connection c) {
        synchronized (lockFor(c.code())) {
            GameDetails details = games.get(c.code());
            c.send(messages.lobby(details, c.player().id()));
            registry.find(c.code()).ifPresent(live -> live.withLock(() -> {
                sendView(live, c);
                return null;
            }));
        }
        play.connected(c.code(), c.player().id());
    }

    public void onDisconnect(Connection c) {
        play.disconnected(c.code(), c.player().id());
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
