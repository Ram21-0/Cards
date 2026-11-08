package com.cards.live;

import com.cards.common.Player;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Clock;

/**
 * The WebSocket of a game. Everything flows server -> client (lobby, view, events, gameOver); the only thing a client
 * may send is {@code {"type":"ping"}}, answered with {@code {"type":"pong"}}. Actions are REST calls (see LIVE.md).
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    /** Nothing a client legitimately sends is longer than this. */
    static final int MAX_CLIENT_MESSAGE_CHARS = 512;
    private static final String ATTR_CONNECTION = "connection";

    private final GameHub hub;
    private final LiveGameService live;
    private final LiveMessages messages;
    private final ObjectMapper json;
    private final Clock clock;

    public GameWebSocketHandler(GameHub hub, LiveGameService live, LiveMessages messages, ObjectMapper json, Clock clock) {
        this.hub = hub;
        this.live = live;
        this.messages = messages;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        String code = (String) raw.getAttributes().get(GameHandshakeInterceptor.ATTR_CODE);
        Player player = (Player) raw.getAttributes().get(GameHandshakeInterceptor.ATTR_PLAYER);
        // sends may come from several threads (the timer, REST calls): serialise them, and cut off a client that cannot keep up
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 256 * 1024);
        Connection connection = new Connection(safe, code, player, clock.instant());
        raw.getAttributes().put(ATTR_CONNECTION, connection);
        hub.add(connection);
        live.onConnect(connection);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        Connection connection = (Connection) raw.getAttributes().get(ATTR_CONNECTION);
        if (connection == null) return;
        connection.touch(clock.instant());
        if (message.getPayloadLength() > MAX_CLIENT_MESSAGE_CHARS) {
            connection.close(CloseStatus.POLICY_VIOLATION.withReason("Message too long"));
            return;
        }
        try {
            JsonNode node = json.readTree(message.getPayload());
            if ("ping".equals(node.path("type").asText())) connection.send(messages.pong());
        } catch (Exception notJson) {
            connection.send(messages.error("Messages must be JSON, e.g. {\"type\":\"ping\"}"));
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession raw, PongMessage message) {
        Connection connection = (Connection) raw.getAttributes().get(ATTR_CONNECTION);
        if (connection != null) connection.touch(clock.instant());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Connection connection = (Connection) raw.getAttributes().get(ATTR_CONNECTION);
        if (connection == null) return;
        hub.remove(connection);
        live.onDisconnect(connection);
    }

    @Override
    public void handleTransportError(WebSocketSession raw, Throwable exception) {
        Connection connection = (Connection) raw.getAttributes().get(ATTR_CONNECTION);
        if (connection != null) connection.close(CloseStatus.SERVER_ERROR);
    }
}
