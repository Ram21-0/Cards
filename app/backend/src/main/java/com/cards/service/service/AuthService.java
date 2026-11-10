package com.cards.service.service;

import com.cards.common.ConflictException;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.UnauthorizedException;
import com.cards.service.db.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final GoogleTokenVerifier google;
    private final NonceStore nonces;
    private final SessionService sessions;
    private final PlayerRepository players;

    /** A signed-in player and the session that was just created for them. */
    public record Login(Player player, SessionService.NewSession session) {
    }

    public String newNonce() {
        return nonces.issue();
    }

    /**
     * Verifies the Google ID token, then finds or creates the player by Google's {@code sub} and starts a session.
     * The nonce is checked after the token so that a bad token does not burn someone's nonce.
     */
    @Transactional
    public Login loginWithGoogle(String credential) {
        GoogleIdentity identity = google.verify(credential);
        if (identity.sub() == null || identity.sub().isBlank()) throw new UnauthorizedException("Invalid Google token");
        if (!identity.emailVerified()) throw new UnauthorizedException("Google account email is not verified");
        if (!nonces.consume(identity.nonce())) throw new UnauthorizedException("Invalid or expired nonce");

        Player player = players.upsertGoogle(identity.sub(), identity.email(), identity.name(), identity.picture());
        return new Login(player, sessions.create(player.id()));
    }

    /** Local development only (guarded by app.dev-login.enabled): sign in as a username, creating it if needed. */
    @Transactional
    public Login devLogin(String rawUsername) {
        String username = Usernames.normalize(rawUsername);
        Player player = players.findByUsername(username)
                .or(() -> players.insertDev(username))
                .orElseThrow(() -> new ConflictException("Username already taken"));
        return new Login(player, sessions.create(player.id()));
    }

    /** Choose or change the username. 400 invalid, 409 taken. Setting your own current name again is fine. */
    @Transactional
    public Player setUsername(UUID playerId, String rawUsername) {
        String username = Usernames.normalize(rawUsername);
        try {
            return players.updateUsername(playerId, username)
                    .orElseThrow(() -> new ConflictException("Username already taken"));
        } catch (DuplicateKeyException race) {                      // two people picked it at the same moment
            throw new ConflictException("Username already taken");
        }
    }

    /** Ends every session of the player and wipes their personal data (the row stays so game history is intact). */
    @Transactional
    public void deleteAccount(UUID playerId) {
        if (players.findById(playerId).isEmpty()) throw new NotFoundException("Player not found");
        sessions.logoutEverywhere(playerId);
        players.anonymize(playerId);
    }

    public void logout(String token) {
        sessions.logout(token);
    }
}
