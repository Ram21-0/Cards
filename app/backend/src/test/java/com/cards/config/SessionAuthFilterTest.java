package com.cards.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which paths demand the X-Requested-With header when a cookie session is used for a state-changing request. */
class SessionAuthFilterTest {

    @Test
    void protectsTheAccountGameCreationAndJoinPaths() {
        for (String path : new String[]{"/api/me", "/api/auth/logout", "/api/games", "/api/games/K7Q2XM/join", "/api/games/k7q2xm/join"}) {
            assertTrue(SessionAuthFilter.requiresHeader(path), path);
        }
    }

    @Test
    void doesNotTouchTheRest() {
        for (String path : new String[]{"/api/decks", "/api/games/K7Q2XM", "/api/games/K7Q2XM/join/extra", "/api/games//join",
                "/api/auth/google", "/api/auth/nonce", "/api/dev/login", "/config.js", "/"}) {
            assertFalse(SessionAuthFilter.requiresHeader(path), path);
        }
    }
}
