package com.cards.service.service;

import com.cards.TestClock;
import com.cards.common.Player;
import com.cards.common.SessionRecord;
import com.cards.service.db.PlayerRepository;
import com.cards.service.db.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceTest {

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final TestClock clock = TestClock.atNow();
    private final SessionService service = new SessionService(sessions, players, clock, 30);

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final Player PLAYER = Player.builder().id(PLAYER_ID).username("ann").createdAt(Instant.now()).build();

    /** Creates a session and returns the raw token, with the repository wired to "remember" it. */
    private String startSession() {
        SessionService.NewSession s = service.create(PLAYER_ID);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> expires = ArgumentCaptor.forClass(Instant.class);
        verify(sessions).insert(hash.capture(), any(), expires.capture());
        when(sessions.find(hash.getValue())).thenReturn(Optional.of(new SessionRecord(hash.getValue(), PLAYER_ID, expires.getValue(), clock.instant())));
        when(players.findById(PLAYER_ID)).thenReturn(Optional.of(PLAYER));
        return s.token();
    }

    @Test
    void storesOnlyTheHashNeverTheToken() {
        SessionService.NewSession s = service.create(PLAYER_ID);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(sessions).insert(stored.capture(), any(), any());
        assertNotEquals(s.token(), stored.getValue());
        assertEquals(SessionService.hash(s.token()), stored.getValue());
        assertEquals(64, stored.getValue().length(), "hex SHA-256");
    }

    @Test
    void tokensAreLongRandomAndUnique() {
        String a = service.create(PLAYER_ID).token();
        String b = service.create(PLAYER_ID).token();
        assertNotEquals(a, b);
        assertTrue(a.length() >= 43, "256 bits, base64url");
        assertTrue(a.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void sessionExpiresAfterTheConfiguredDays() {
        assertEquals(clock.instant().plus(Duration.ofDays(30)), service.create(PLAYER_ID).expiresAt());
    }

    @Test
    void validTokenAuthenticatesThePlayer() {
        String token = startSession();
        assertEquals(Optional.of(PLAYER), service.authenticate(token));
    }

    @Test
    void unknownBlankOrMissingTokensAreAnonymous() {
        assertTrue(service.authenticate("who-knows").isEmpty());
        assertTrue(service.authenticate("").isEmpty());
        assertTrue(service.authenticate("  ").isEmpty());
        assertTrue(service.authenticate(null).isEmpty());
    }

    @Test
    void anExpiredSessionIsRejectedAndRemoved() {
        String token = startSession();
        clock.advance(Duration.ofDays(30).plusSeconds(1));
        assertTrue(service.authenticate(token).isEmpty());
        verify(sessions).delete(SessionService.hash(token));
    }

    @Test
    void aSessionIsStillValidJustBeforeExpiry() {
        String token = startSession();
        clock.advance(Duration.ofDays(30).minusSeconds(1));
        assertTrue(service.authenticate(token).isPresent());
    }

    @Test
    void aSessionWhosePlayerIsGoneIsRemoved() {
        String token = startSession();
        when(players.findById(PLAYER_ID)).thenReturn(Optional.empty());
        assertTrue(service.authenticate(token).isEmpty());
        verify(sessions).delete(SessionService.hash(token));
    }

    @Test
    void logoutDeletesTheSessionSoTheTokenStopsWorking() {
        String token = startSession();
        service.logout(token);
        verify(sessions).delete(SessionService.hash(token));
        service.logout(null);                                         // harmless
        service.logout("");
        verify(sessions, never()).delete(SessionService.hash(""));
    }

    @Test
    void logoutEverywhereEndsAllSessionsOfThePlayer() {
        service.logoutEverywhere(PLAYER_ID);
        verify(sessions).deleteAllFor(PLAYER_ID);
    }

    @Test
    void lastSeenIsTouchedOnlyOccasionally() {
        String token = startSession();
        service.authenticate(token);
        verify(sessions, never()).touch(anyString(), any());          // just created: no write on every request

        clock.advance(Duration.ofMinutes(6));
        service.authenticate(token);
        verify(sessions).touch(SessionService.hash(token), clock.instant());
    }

    @Test
    void creatingASessionPurgesExpiredOnes() {
        service.create(PLAYER_ID);
        verify(sessions).deleteExpired(clock.instant());
        assertEquals(Timestamp.from(clock.instant()).toInstant(), clock.instant());
    }
}
