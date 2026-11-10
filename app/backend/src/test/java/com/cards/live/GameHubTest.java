package com.cards.live;

import com.cards.TestClock;
import com.cards.common.Player;
import com.cards.live.LiveTestKit.FakeSocket;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;

import static com.cards.live.LiveTestKit.CODE;
import static com.cards.live.LiveTestKit.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameHubTest {

    private final TestClock clock = TestClock.atNow();
    private final GameHub hub = new GameHub(clock, 90);
    private final Player ann = player("ann"), bob = player("bob");

    @Test
    void connectionsAreTrackedPerGame() {
        FakeSocket a = new FakeSocket(), b = new FakeSocket(), other = new FakeSocket();
        Connection ca = a.connect(CODE, ann, clock.instant()), cb = b.connect(CODE, bob, clock.instant()), co = other.connect("OTHER1", ann, clock.instant());
        hub.add(ca);
        hub.add(cb);
        hub.add(co);
        assertEquals(2, hub.connections(CODE).size());
        assertEquals(1, hub.connections("OTHER1").size());
        assertTrue(hub.connections("NOBODY").isEmpty());
        assertTrue(hub.isConnected(CODE, ann.id()));
        assertTrue(hub.isConnected(CODE, bob.id()));

        hub.remove(cb);
        assertFalse(hub.isConnected(CODE, bob.id()));
        hub.remove(ca);
        assertTrue(hub.connections(CODE).isEmpty());
    }

    @Test
    void aClosedSocketDoesNotCountAsConnected() {
        FakeSocket a = new FakeSocket();
        hub.add(a.connect(CODE, ann, clock.instant()));
        a.open = false;
        assertFalse(hub.isConnected(CODE, ann.id()));
    }

    @Test
    void closingAPlayerClosesAllTheirSocketsInThatGameOnly() {
        FakeSocket a1 = new FakeSocket(), a2 = new FakeSocket(), b = new FakeSocket(), elsewhere = new FakeSocket();
        hub.add(a1.connect(CODE, ann, clock.instant()));
        hub.add(a2.connect(CODE, ann, clock.instant()));
        hub.add(b.connect(CODE, bob, clock.instant()));
        hub.add(elsewhere.connect("OTHER1", ann, clock.instant()));
        hub.closePlayer(CODE, ann.id(), CloseStatus.NORMAL);
        assertEquals(CloseStatus.NORMAL, a1.closedWith);
        assertEquals(CloseStatus.NORMAL, a2.closedWith);
        assertNull(b.closedWith);
        assertNull(elsewhere.closedWith);
    }

    @Test
    void pingsKeepConnectionsAliveAndQuietOnesAreDropped() {
        FakeSocket alive = new FakeSocket(), quiet = new FakeSocket();
        Connection ca = alive.connect(CODE, ann, clock.instant()), cq = quiet.connect(CODE, bob, clock.instant());
        hub.add(ca);
        hub.add(cq);

        clock.advance(Duration.ofSeconds(60));
        ca.touch(clock.instant());                                   // ann's browser answered a ping
        hub.pingAll();
        assertEquals(1, alive.pings);
        assertEquals(1, quiet.pings, "not stale yet: 60 s < 90 s");

        clock.advance(Duration.ofSeconds(40));                       // bob has been silent for 100 s
        hub.pingAll();
        assertEquals(2, alive.pings);
        assertNotNull(quiet.closedWith, "a connection silent for more than 90 s is closed");
        assertEquals(CloseStatus.GOING_AWAY, quiet.closedWith);
        assertNull(alive.closedWith);
    }

    @Test
    void sendingToABrokenSocketClosesItInsteadOfThrowing() throws Exception {
        FakeSocket a = new FakeSocket();
        org.mockito.Mockito.doThrow(new java.io.IOException("broken pipe")).when(a.session).sendMessage(org.mockito.ArgumentMatchers.any());
        Connection c = a.connect(CODE, ann, clock.instant());
        assertFalse(c.send("{}"));
        assertEquals(CloseStatus.SESSION_NOT_RELIABLE, a.closedWith);
    }
}
