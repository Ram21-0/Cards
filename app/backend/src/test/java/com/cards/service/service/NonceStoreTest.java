package com.cards.service.service;

import com.cards.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonceStoreTest {

    private final TestClock clock = TestClock.atNow();
    private final NonceStore store = new NonceStore(clock);

    @Test
    void anIssuedNonceWorksExactlyOnce() {
        String nonce = store.issue();
        assertTrue(store.consume(nonce));
        assertFalse(store.consume(nonce), "a replayed nonce must be rejected");
    }

    @Test
    void unknownOrMissingNoncesAreRejected() {
        assertFalse(store.consume("never-issued"));
        assertFalse(store.consume(null));
        assertFalse(store.consume(""));
    }

    @Test
    void aNonceExpiresAfterFiveMinutes() {
        String nonce = store.issue();
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertTrue(store.consume(nonce));

        String late = store.issue();
        clock.advance(Duration.ofMinutes(5));
        assertFalse(store.consume(late));
    }

    @Test
    void noncesAreUnpredictableAndUnique() {
        String a = store.issue();
        String b = store.issue();
        assertNotEquals(a, b);
        assertTrue(a.length() >= 20, "at least 128 bits, base64url");
        assertTrue(a.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void flooding_dropsTheOldestInsteadOfGrowingForever() {
        String first = store.issue();
        for (int i = 0; i < NonceStore.MAX_ENTRIES; i++) store.issue();
        assertFalse(store.consume(first), "the oldest nonce was evicted");
        assertEquals(true, store.consume(store.issue()));
    }
}
