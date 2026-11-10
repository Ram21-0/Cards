package com.cards.live;

import com.cards.TestClock;
import com.cards.common.Player;
import com.cards.live.LiveTestKit.FakeSocket;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.time.Duration;

import static com.cards.live.LiveTestKit.CODE;
import static com.cards.live.LiveTestKit.FRONTEND;
import static com.cards.live.LiveTestKit.json;
import static com.cards.live.LiveTestKit.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GameWebSocketHandlerTest {

    private final TestClock clock = TestClock.atNow();
    private final GameHub hub = new GameHub(clock, 90);
    private final LiveGameService live = mock(LiveGameService.class);
    private final LiveMessages messages = new LiveMessages(json(), clock, FRONTEND);
    private final GameWebSocketHandler handler = new GameWebSocketHandler(hub, live, messages, json(), clock);
    private final Player ann = player("ann");

    private FakeSocket connected() {
        FakeSocket socket = new FakeSocket();
        socket.attributes.put(GameHandshakeInterceptor.ATTR_CODE, CODE);
        socket.attributes.put(GameHandshakeInterceptor.ATTR_PLAYER, ann);
        handler.afterConnectionEstablished(socket.session);
        return socket;
    }

    @Test
    void openingARegistersTheConnectionAndBringsItUpToDate() {
        FakeSocket socket = connected();
        assertEquals(1, hub.connections(CODE).size());
        verify(live).onConnect(any(Connection.class));
        assertTrue(socket.sent.isEmpty(), "the live service sends the first messages, not the handler");
    }

    @Test
    void closingUnregistersAndTellsTheLiveService() {
        FakeSocket socket = connected();
        handler.afterConnectionClosed(socket.session, CloseStatus.NORMAL);
        assertTrue(hub.connections(CODE).isEmpty());
        verify(live).onDisconnect(any(Connection.class));
    }

    @Test
    void aPingIsAnsweredWithAPong() throws Exception {
        FakeSocket socket = connected();
        handler.handleMessage(socket.session, new TextMessage("{\"type\":\"ping\"}"));
        assertEquals(1, socket.ofType("pong").size());
    }

    @Test
    void anythingElseIsIgnoredAndGarbageGetsAnError() throws Exception {
        FakeSocket socket = connected();
        handler.handleMessage(socket.session, new TextMessage("{\"type\":\"hello\"}"));
        assertTrue(socket.sent.isEmpty());
        handler.handleMessage(socket.session, new TextMessage("not json"));
        assertEquals(1, socket.ofType("error").size());
    }

    @Test
    void aTooLongMessageClosesTheConnection() throws Exception {
        FakeSocket socket = connected();
        handler.handleMessage(socket.session, new TextMessage("x".repeat(GameWebSocketHandler.MAX_CLIENT_MESSAGE_CHARS + 1)));
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), socket.closedWith.getCode());
    }

    @Test
    void anyTrafficCountsAsAlive() throws Exception {
        FakeSocket socket = connected();
        Connection c = hub.connections(CODE).get(0);
        clock.advance(Duration.ofSeconds(80));
        handler.handleMessage(socket.session, new TextMessage("{\"type\":\"ping\"}"));
        assertEquals(clock.instant(), c.lastSeen());
        verify(live, never()).onDisconnect(any());
    }
}
