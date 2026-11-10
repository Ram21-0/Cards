package com.cards.live;

import com.cards.TestClock;
import com.cards.common.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimiterTest {

    private final TestClock clock = TestClock.atNow();
    private final RateLimiter limiter = new RateLimiter(clock, 5, 10);

    @Test
    void allowsUpToTheLimitInAWindowThenRefuses() {
        UUID p = UUID.randomUUID();
        for (int i = 0; i < 5; i++) assertDoesNotThrow(() -> limiter.check(p));
        assertThrows(TooManyRequestsException.class, () -> limiter.check(p));
        assertThrows(TooManyRequestsException.class, () -> limiter.check(p));
    }

    @Test
    void theWindowResetsAfterTenSeconds() {
        UUID p = UUID.randomUUID();
        for (int i = 0; i < 5; i++) limiter.check(p);
        clock.advance(Duration.ofSeconds(9));
        assertThrows(TooManyRequestsException.class, () -> limiter.check(p));
        clock.advance(Duration.ofSeconds(1));
        assertDoesNotThrow(() -> limiter.check(p));
    }

    @Test
    void eachPlayerHasTheirOwnAllowance() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (int i = 0; i < 5; i++) limiter.check(a);
        assertThrows(TooManyRequestsException.class, () -> limiter.check(a));
        assertDoesNotThrow(() -> limiter.check(b));
    }

    @Test
    void theDefaultIsFarAboveWhatAPersonCanClick() {
        RateLimiter defaults = new RateLimiter(clock, 30, 10);
        UUID p = UUID.randomUUID();
        for (int i = 0; i < 30; i++) defaults.check(p);
        assertThrows(TooManyRequestsException.class, () -> defaults.check(p));
    }
}
