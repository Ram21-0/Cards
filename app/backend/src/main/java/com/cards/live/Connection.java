package com.cards.live;

import com.cards.common.Player;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

/** One open WebSocket of one signed-in player in one game. Sends never throw: a failing socket is closed instead. */
public final class Connection {

    private final WebSocketSession session;
    private final String code;
    private final Player player;
    private volatile Instant lastSeen;

    public Connection(WebSocketSession session, String code, Player player, Instant now) {
        this.session = session;
        this.code = code;
        this.player = player;
        this.lastSeen = now;
    }

    public String code() {
        return code;
    }

    public Player player() {
        return player;
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    /** The client said or answered something: it is alive. */
    public void touch(Instant now) {
        lastSeen = now;
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    /** @return false when the message could not be delivered (the connection is then closed) */
    public boolean send(String json) {
        try {
            if (!session.isOpen()) return false;
            session.sendMessage(new TextMessage(json));
            return true;
        } catch (Exception e) {
            close(CloseStatus.SESSION_NOT_RELIABLE);
            return false;
        }
    }

    public void ping() {
        try {
            if (session.isOpen()) session.sendMessage(new org.springframework.web.socket.PingMessage());
        } catch (Exception e) {
            close(CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    public void close(CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // already gone
        }
    }
}
