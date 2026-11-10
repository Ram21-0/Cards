package com.cards.service.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One-time nonces for the Google sign-in (replay protection): the browser asks for one, passes it to Google, and the
 * ID token that comes back must carry the same nonce. Each nonce works once and expires after a few minutes.
 *
 * Kept in memory — fine for a single server instance; with several instances move it to the database or a shared cache.
 */
@Component
public class NonceStore {

    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_ENTRIES = 10_000;

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Map<String, Instant> expiryByNonce = new LinkedHashMap<>();     // insertion order = oldest first

    public NonceStore(Clock clock) {
        this.clock = clock;
    }

    public synchronized String issue() {
        purgeExpired();
        while (expiryByNonce.size() >= MAX_ENTRIES) {                              // flooded: drop the oldest
            Iterator<String> it = expiryByNonce.keySet().iterator();
            it.next();
            it.remove();
        }
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        expiryByNonce.put(nonce, clock.instant().plus(TTL));
        return nonce;
    }

    /** True exactly once per issued, unexpired nonce. */
    public synchronized boolean consume(String nonce) {
        if (nonce == null) return false;
        Instant expiry = expiryByNonce.remove(nonce);
        return expiry != null && expiry.isAfter(clock.instant());
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        expiryByNonce.values().removeIf(expiry -> !expiry.isAfter(now));
    }
}
