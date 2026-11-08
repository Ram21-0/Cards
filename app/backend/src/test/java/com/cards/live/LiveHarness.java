package com.cards.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** A real WebSocket client for the integration tests: collects every message, lets a test wait for the next one it cares about. */
final class LiveHarness {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LiveHarness() {
    }

    static final class Client implements WebSocket.Listener {
        private final List<JsonNode> messages = new ArrayList<>();
        private final StringBuilder partial = new StringBuilder();
        private int cursor;
        private volatile Integer closeCode;
        WebSocket socket;

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    JsonNode node = JSON.readTree(partial.toString());
                    synchronized (this) {
                        messages.add(node);
                        notifyAll();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("the server sent something that is not JSON: " + partial, e);
                } finally {
                    partial.setLength(0);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            synchronized (this) {
                closeCode = statusCode;
                notifyAll();
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            synchronized (this) {
                closeCode = closeCode == null ? -1 : closeCode;
                notifyAll();
            }
        }

        /** The next not-yet-consumed message matching {@code wanted} (earlier non-matching ones are skipped over). */
        synchronized JsonNode await(Predicate<JsonNode> wanted, long timeoutMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                while (cursor < messages.size()) {
                    JsonNode m = messages.get(cursor++);
                    if (wanted.test(m)) return m;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new AssertionError("timed out waiting for a message; received so far: " + messages);
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }

        JsonNode awaitType(String type) {
            return await(m -> type.equals(m.path("type").asText()), 5_000);
        }

        /** Marks everything received so far as consumed. */
        synchronized void skipPending() {
            cursor = messages.size();
        }

        synchronized List<JsonNode> all() {
            return new ArrayList<>(messages);
        }

        /** Every message received, as text (for "this must not appear anywhere" checks). */
        synchronized List<String> allText() {
            return messages.stream().map(JsonNode::toString).toList();
        }

        boolean awaitClosed(long timeoutMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            synchronized (this) {
                while (closeCode == null) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) return false;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            }
        }

        Integer closeCode() {
            return closeCode;
        }

        void send(String text) {
            socket.sendText(text, true).join();
        }

        void close() {
            if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    /** Opens the game's WebSocket as a browser would (session cookie + Origin). */
    static Client connect(int port, String code, String sessionToken, String origin) throws Exception {
        Client client = new Client();
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        if (sessionToken != null) builder.header("Cookie", "session=" + sessionToken);
        if (origin != null) builder.header("Origin", origin);
        client.socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/ws/games/" + code), client).get(10, TimeUnit.SECONDS);
        return client;
    }

    /** The HTTP status the handshake was refused with, or 101 when the connection was accepted. */
    static int handshakeStatus(int port, String code, String sessionToken, String origin) throws Exception {
        try {
            Client client = connect(port, code, sessionToken, origin);
            client.close();
            return 101;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException refused) return refused.getResponse().statusCode();
            throw e;
        }
    }
}
