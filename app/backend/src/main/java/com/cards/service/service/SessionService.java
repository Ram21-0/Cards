package com.cards.service.service;

import com.cards.common.Player;
import com.cards.common.SessionRecord;
import com.cards.service.db.PlayerRepository;
import com.cards.service.db.SessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessions are random 256-bit tokens. Only the SHA-256 hash is stored, so a database leak does not leak usable
 * sessions. The same token is accepted from the cookie or as a Bearer header.
 */
@Service
public class SessionService {

    private static final Duration TOUCH_EVERY = Duration.ofMinutes(5);

    private final SessionRepository sessions;
    private final PlayerRepository players;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public SessionService(SessionRepository sessions, PlayerRepository players, Clock clock,
                          @Value("${app.session.ttl-days:30}") int ttlDays) {
        this.sessions = sessions;
        this.players = players;
        this.clock = clock;
        this.ttl = Duration.ofDays(ttlDays);
    }

    /** The raw token (give to the client once) and when it stops working. */
    public record NewSession(String token, Instant expiresAt) {
    }

    public NewSession create(UUID playerId) {
        Instant now = clock.instant();
        sessions.deleteExpired(now);                                    // cheap housekeeping
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(ttl);
        sessions.insert(hash(token), playerId, expiresAt);
        return new NewSession(token, expiresAt);
    }

    /** The player behind a token, or empty when the token is unknown, expired, or its player is gone. */
    public Optional<Player> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String hash = hash(token);
        Optional<SessionRecord> found = sessions.find(hash);
        if (found.isEmpty()) return Optional.empty();

        SessionRecord session = found.get();
        Instant now = clock.instant();
        if (!session.expiresAt().isAfter(now)) {
            sessions.delete(hash);
            return Optional.empty();
        }
        Optional<Player> player = players.findById(session.playerId());
        if (player.isEmpty()) {
            sessions.delete(hash);
            return Optional.empty();
        }
        if (session.lastSeen().plus(TOUCH_EVERY).isBefore(now)) sessions.touch(hash, now);
        return player;
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) sessions.delete(hash(token));
    }

    public void logoutEverywhere(UUID playerId) {
        sessions.deleteAllFor(playerId);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);                          // SHA-256 is always available
        }
    }
}
