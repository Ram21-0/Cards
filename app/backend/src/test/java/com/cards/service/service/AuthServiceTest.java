package com.cards.service.service;

import com.cards.TestClock;
import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.ServiceUnavailableException;
import com.cards.common.UnauthorizedException;
import com.cards.service.db.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Uses a fake Google verifier: no test ever calls Google. */
class AuthServiceTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Player ANN = Player.builder().id(ID).googleSub("sub-1").displayName("Ann").createdAt(Instant.now()).build();

    private final TestClock clock = TestClock.atNow();
    private final NonceStore nonces = new NonceStore(clock);
    private final SessionService sessions = mock(SessionService.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private GoogleIdentity identity = new GoogleIdentity("sub-1", "ann@example.com", true, "Ann", "https://pic", null);
    private RuntimeException verifierFailure;
    private final GoogleTokenVerifier google = credential -> {
        if (verifierFailure != null) throw verifierFailure;
        return identity;
    };
    private final AuthService service = new AuthService(google, nonces, sessions, players);

    private void identityWithFreshNonce() {
        identity = new GoogleIdentity("sub-1", "ann@example.com", true, "Ann", "https://pic", nonces.issue());
        when(players.upsertGoogle("sub-1", "ann@example.com", "Ann", "https://pic")).thenReturn(ANN);
        when(sessions.create(ID)).thenReturn(new SessionService.NewSession("raw-token", clock.instant().plus(Duration.ofDays(30))));
    }

    // ------------------------------------------------------------ Google login

    @Test
    void googleLoginCreatesTheAccountAndAStartsASession() {
        identityWithFreshNonce();
        AuthService.Login login = service.loginWithGoogle("credential");
        assertEquals(ANN, login.player());
        assertEquals("raw-token", login.session().token());
    }

    @Test
    void accountIsLookedUpByGoogleSubNotByEmail() {
        identityWithFreshNonce();
        service.loginWithGoogle("credential");
        verify(players).upsertGoogle(eq("sub-1"), any(), any(), any());
    }

    @Test
    void aNonceCannotBeReplayed() {
        identityWithFreshNonce();
        service.loginWithGoogle("credential");
        assertThrows(UnauthorizedException.class, () -> service.loginWithGoogle("credential"));   // same token, same nonce
    }

    @Test
    void aTokenWithAnUnknownNonceIsRejected() {
        identityWithFreshNonce();
        identity = new GoogleIdentity("sub-1", "ann@example.com", true, "Ann", "https://pic", "forged-nonce");
        assertThrows(UnauthorizedException.class, () -> service.loginWithGoogle("credential"));
        verify(players, never()).upsertGoogle(anyString(), any(), any(), any());
    }

    @Test
    void aTokenWithoutANonceIsRejected() {
        identityWithFreshNonce();
        identity = new GoogleIdentity("sub-1", "ann@example.com", true, "Ann", "https://pic", null);
        assertThrows(UnauthorizedException.class, () -> service.loginWithGoogle("credential"));
    }

    @Test
    void anExpiredNonceIsRejected() {
        identityWithFreshNonce();
        clock.advance(Duration.ofMinutes(6));
        assertThrows(UnauthorizedException.class, () -> service.loginWithGoogle("credential"));
    }

    @Test
    void anUnverifiedEmailIsRejected() {
        identity = new GoogleIdentity("sub-1", "ann@example.com", false, "Ann", "https://pic", nonces.issue());
        assertThrows(UnauthorizedException.class, () -> service.loginWithGoogle("credential"));
        verify(sessions, never()).create(any());
    }

    @Test
    void aBadTokenDoesNotBurnTheNonce() {
        String nonce = nonces.issue();
        verifierFailure = new UnauthorizedException("Invalid Google token");
        assertThrows(UnauthorizedException.class, () -> service.loginWithGoogle("forged"));
        assertTrue(nonces.consume(nonce), "the legitimate user can still use their nonce");
    }

    @Test
    void notConfiguredGoogleSignInIsReportedAsUnavailable() {
        verifierFailure = new ServiceUnavailableException("Google sign-in is not configured");
        assertThrows(ServiceUnavailableException.class, () -> service.loginWithGoogle("credential"));
    }

    @Test
    void aTokenWithoutASubjectIsRejected() {
        identity = new GoogleIdentity(" ", "ann@example.com", true, "Ann", "https://pic", nonces.issue());
        assertThrows(UnauthorizedException.class, () -> service.loginWithGoogle("credential"));
    }

    // ------------------------------------------------------------ dev login

    @Test
    void devLoginReusesAnExistingUsername() {
        Player existing = Player.builder().id(ID).username("ann").build();
        when(players.findByUsername("ann")).thenReturn(Optional.of(existing));
        when(sessions.create(ID)).thenReturn(new SessionService.NewSession("t", clock.instant()));
        assertEquals(existing, service.devLogin("  ANN ").player());
        verify(players, never()).insertDev(anyString());
    }

    @Test
    void devLoginCreatesTheUsernameWhenNew() {
        Player created = Player.builder().id(ID).username("bob").build();
        when(players.findByUsername("bob")).thenReturn(Optional.empty());
        when(players.insertDev("bob")).thenReturn(Optional.of(created));
        when(sessions.create(ID)).thenReturn(new SessionService.NewSession("t", clock.instant()));
        assertEquals(created, service.devLogin("bob").player());
    }

    @Test
    void devLoginValidatesTheUsername() {
        assertThrows(BadRequestException.class, () -> service.devLogin("not valid!"));
    }

    // ------------------------------------------------------------ username

    @Test
    void choosingAUsernameNormalizesAndSaves() {
        Player renamed = Player.builder().id(ID).username("ann_1").build();
        when(players.updateUsername(ID, "ann_1")).thenReturn(Optional.of(renamed));
        assertEquals("ann_1", service.setUsername(ID, "  Ann_1 ").username());
    }

    @Test
    void invalidUsernamesAreRejectedBeforeTouchingTheDatabase() {
        for (String bad : new String[]{null, "", "  ", "toolongname", "has space", "dash-ed", "dot.ted", "ünï"}) {
            assertThrows(BadRequestException.class, () -> service.setUsername(ID, bad), "should reject: " + bad);
        }
        verify(players, never()).updateUsername(any(), anyString());
    }

    @Test
    void aTakenUsernameIsAConflict() {
        when(players.updateUsername(ID, "ann")).thenReturn(Optional.empty());
        assertThrows(ConflictException.class, () -> service.setUsername(ID, "ann"));
    }

    @Test
    void aRaceOnTheUniqueIndexIsAlsoAConflict() {
        when(players.updateUsername(ID, "ann")).thenThrow(new DuplicateKeyException("duplicate"));
        assertThrows(ConflictException.class, () -> service.setUsername(ID, "ann"));
    }

    // ------------------------------------------------------------ logout / delete

    @Test
    void logoutEndsTheSession() {
        service.logout("raw-token");
        verify(sessions).logout("raw-token");
    }

    @Test
    void deletingTheAccountEndsAllSessionsAndWipesPersonalData() {
        when(players.findById(ID)).thenReturn(Optional.of(ANN));
        service.deleteAccount(ID);
        verify(sessions).logoutEverywhere(ID);
        verify(players).anonymize(ID);
    }

    @Test
    void deletingAnUnknownAccountIsNotFound() {
        when(players.findById(ID)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.deleteAccount(ID));
        verify(players, never()).anonymize(any());
    }
}
