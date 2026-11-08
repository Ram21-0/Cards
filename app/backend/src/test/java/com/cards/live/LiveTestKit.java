package com.cards.live;

import com.cards.TestClock;
import com.cards.common.Card;
import com.cards.common.ComparisonStrategy;
import com.cards.common.Deck;
import com.cards.common.DifficultyLevel;
import com.cards.common.DisplayFormat;
import com.cards.common.Game;
import com.cards.common.GameStatus;
import com.cards.common.Player;
import com.cards.common.SeatInfo;
import com.cards.common.SeatKind;
import com.cards.common.Stat;
import com.cards.common.StatDefinition;
import com.cards.common.ValueType;
import com.cards.common.Visibility;
import com.cards.game.GameEngine;
import com.cards.game.GameSettings;
import com.cards.game.SeatSpec;
import com.cards.service.service.GameDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared fixtures for the live-play tests: players, a deck, known hands, and a fake WebSocket. */
final class LiveTestKit {

    static final UUID DECK_ID = UUID.randomUUID();
    static final String CODE = "K7Q2XM";
    static final String FRONTEND = "http://localhost:8081";

    static final StatDefinition POWER = StatDefinition.builder().deckId(DECK_ID).key("power").label("Power").valueType(ValueType.INT)
            .comparisonStrategy(ComparisonStrategy.HIGHER_WINS).displayFormat(DisplayFormat.PLAIN).displayOrder(1).build();
    static final StatDefinition SPEED = StatDefinition.builder().deckId(DECK_ID).key("speed").label("Speed").valueType(ValueType.INT)
            .comparisonStrategy(ComparisonStrategy.LOWER_WINS).displayFormat(DisplayFormat.WITH_UNIT).unit("s").displayOrder(2).build();
    static final List<StatDefinition> STATS = List.of(POWER, SPEED);
    static final Deck DECK = Deck.builder().id(DECK_ID).name("Test deck").visibility(Visibility.PUBLIC).cardCount(12).stats(STATS).build();

    private LiveTestKit() {
    }

    static ObjectMapper json() {
        return new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    static Player player(String name) {
        return Player.builder().id(UUID.randomUUID()).username(name).createdAt(Instant.now()).build();
    }

    /** A card whose name and id are unmistakable in a JSON text: id 1000*seat+k, name "S{seat}-{k}". */
    static Card card(int seat, int k, int power, int speed) {
        return Card.builder().id(1000 * seat + k).deckId(DECK_ID).name("S" + seat + "-" + k).metadata(Map.of())
                .stats(List.of(new Stat("power", BigDecimal.valueOf(power)), new Stat("speed", BigDecimal.valueOf(speed)))).build();
    }

    static SeatInfo human(int seat, Player p) {
        return new SeatInfo(seat, SeatKind.HUMAN, p.username(), null, p.id(), null);
    }

    static SeatInfo cpu(int seat, DifficultyLevel level) {
        return new SeatInfo(seat, SeatKind.CPU, null, level, null, null);
    }

    static Game game(GameStatus status, UUID hostId) {
        return Game.builder().id(UUID.randomUUID()).joinCode(CODE).deckId(DECK_ID).hostId(hostId).maxPlayers(4).maxRounds(500)
                .chooseThreshold(3).clashCallSeconds(5).status(status).build();
    }

    static GameDetails details(GameStatus status, Player host, List<SeatInfo> seats) {
        return new GameDetails(game(status, host.id()), DECK, seats);
    }

    /** An engine with known hands (human seats unless a spec says CPU). The first caller is seat 1. */
    static GameEngine engine(TestClock clock, GameSettings settings, List<SeatSpec> specs, List<List<Card>> hands) {
        GameEngine e = GameEngine.withHands(STATS, specs, settings, hands, 1, 0, new SplittableRandom(1), clock);
        e.pollEvents();
        return e;
    }

    static List<SeatSpec> humans(int n) {
        List<SeatSpec> specs = new ArrayList<>();
        for (int i = 0; i < n; i++) specs.add(SeatSpec.human());
        return specs;
    }

    /** A WebSocketSession that records every text message sent to it. */
    static final class FakeSocket {
        final WebSocketSession session = mock(WebSocketSession.class);
        final List<String> sent = new CopyOnWriteArrayList<>();
        int pings;
        volatile boolean open = true;
        volatile CloseStatus closedWith;
        final Map<String, Object> attributes = new java.util.HashMap<>();

        FakeSocket() {
            when(session.isOpen()).thenAnswer(i -> open);
            when(session.getAttributes()).thenReturn(attributes);
            try {
                doAnswer(i -> {
                    WebSocketMessage<?> m = i.getArgument(0);
                    if (m instanceof TextMessage t) sent.add(t.getPayload());
                    else if (m instanceof PingMessage) pings++;
                    return null;
                }).when(session).sendMessage(any());
                doAnswer(i -> {
                    open = false;
                    closedWith = i.getArgument(0);
                    return null;
                }).when(session).close(any(CloseStatus.class));
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        Connection connect(String code, Player player, Instant now) {
            return new Connection(session, code, player, now);
        }

        List<String> ofType(String type) {
            return sent.stream().filter(s -> s.contains("\"type\":\"" + type + "\"")).toList();
        }

        String last() {
            return sent.get(sent.size() - 1);
        }
    }
}
