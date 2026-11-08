package com.cards.service.controller;

import com.cards.common.Deck;
import com.cards.common.Game;
import com.cards.common.GameStatus;
import com.cards.common.Player;
import com.cards.common.SeatInfo;
import com.cards.common.SeatKind;
import com.cards.common.Visibility;
import com.cards.common.ServiceUnavailableException;
import com.cards.common.UnauthorizedException;
import com.cards.config.ClockConfig;
import com.cards.config.SecurityConfig;
import com.cards.config.SessionCookies;
import com.cards.service.service.AuthService;
import com.cards.live.LiveGameService;
import com.cards.live.RateLimiter;
import com.cards.service.service.GameDetails;
import com.cards.service.service.GameOptions;
import com.cards.service.service.GameService;
import com.cards.service.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level tests of the security rules (no database, no Google). */
@WebMvcTest(controllers = {AuthController.class, MeController.class, GameController.class})
@Import({SecurityConfig.class, SessionCookies.class, ClockConfig.class})
class AuthWebTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Player ANN = Player.builder().id(ID).username("ann").displayName("Ann Example")
            .email("ann@example.com").avatarUrl("https://pic").createdAt(Instant.now()).build();
    private static final Cookie GOOD = new Cookie("session", "good-token");

    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean SessionService sessions;
    @MockitoBean GameService games;
    @MockitoBean LiveGameService live;
    @MockitoBean RateLimiter rateLimiter;

    @BeforeEach
    void knownSessions() {
        when(sessions.authenticate("good-token")).thenReturn(Optional.of(ANN));
        when(sessions.authenticate("expired-or-unknown")).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------ GET /api/me

    @Test
    void meWithoutASessionIs401Json() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Sign in required"));
    }

    @Test
    void meWithAValidCookieReturnsThePlayer() throws Exception {
        mvc.perform(get("/api/me").cookie(GOOD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.username").value("ann"))
                .andExpect(jsonPath("$.displayName").value("Ann Example"))
                .andExpect(jsonPath("$.avatarUrl").value("https://pic"))
                .andExpect(jsonPath("$.email").value("ann@example.com"))
                .andExpect(jsonPath("$.needsUsername").value(false));
    }

    @Test
    void meWithABearerTokenWorksToo() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ann"));
    }

    @Test
    void anExpiredOrUnknownTokenIs401() throws Exception {
        mvc.perform(get("/api/me").cookie(new Cookie("session", "expired-or-unknown"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer expired-or-unknown")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Basic abc")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCookieWithAnotherNameIsIgnored() throws Exception {
        mvc.perform(get("/api/me").cookie(new Cookie("other", "good-token"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aNewPlayerWithoutUsernameIsToldToPickOne() throws Exception {
        Player fresh = Player.builder().id(ID).displayName("New Person").build();
        when(sessions.authenticate("fresh")).thenReturn(Optional.of(fresh));
        mvc.perform(get("/api/me").cookie(new Cookie("session", "fresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.needsUsername").value(true));
    }

    // ------------------------------------------------------------ PUT /api/me

    @Test
    void changingTheUsernameWithACookieNeedsTheXRequestedWithHeader() throws Exception {
        mvc.perform(put("/api/me").cookie(GOOD).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"newname\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Missing X-Requested-With header"));
        verify(auth, never()).setUsername(ID, "newname");
    }

    @Test
    void changingTheUsernameWorksWithTheHeader() throws Exception {
        when(auth.setUsername(ID, "newname")).thenReturn(Player.builder().id(ID).username("newname").build());
        mvc.perform(put("/api/me").cookie(GOOD).header("X-Requested-With", "cards")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"newname\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newname"));
    }

    @Test
    void bearerClientsDoNotNeedTheHeader() throws Exception {
        when(auth.setUsername(ID, "newname")).thenReturn(Player.builder().id(ID).username("newname").build());
        mvc.perform(put("/api/me").header("Authorization", "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"newname\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void usernameErrorsAreReportedAs400And409() throws Exception {
        when(auth.setUsername(ID, "bad!")).thenThrow(new com.cards.common.BadRequestException("Username must be 1-8 characters: letters, digits or underscore"));
        when(auth.setUsername(ID, "taken")).thenThrow(new com.cards.common.ConflictException("Username already taken"));
        mvc.perform(put("/api/me").cookie(GOOD).header("X-Requested-With", "cards").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"bad!\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(put("/api/me").cookie(GOOD).header("X-Requested-With", "cards").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"taken\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Username already taken"));
    }

    @Test
    void changingTheUsernameWithoutASessionIs401() throws Exception {
        mvc.perform(put("/api/me").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ DELETE /api/me

    @Test
    void deletingTheAccountClearsTheCookie() throws Exception {
        MvcResult r = mvc.perform(delete("/api/me").cookie(GOOD).header("X-Requested-With", "cards"))
                .andExpect(status().isNoContent()).andReturn();
        verify(auth).deleteAccount(ID);
        assertTrue(r.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
    }

    @Test
    void deletingTheAccountWithoutTheHeaderIs403() throws Exception {
        mvc.perform(delete("/api/me").cookie(GOOD)).andExpect(status().isForbidden());
        verify(auth, never()).deleteAccount(ID);
    }

    @Test
    void deletingTheAccountWithoutASessionIs401() throws Exception {
        mvc.perform(delete("/api/me")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ nonce + Google login

    @Test
    void nonceIsPublic() throws Exception {
        when(auth.newNonce()).thenReturn("abc123");
        mvc.perform(post("/api/auth/nonce")).andExpect(status().isOk()).andExpect(jsonPath("$.nonce").value("abc123"));
    }

    @Test
    void googleLoginSetsAHardenedCookieAndReturnsThePlayer() throws Exception {
        when(auth.loginWithGoogle("google-token")).thenReturn(new AuthService.Login(ANN,
                new SessionService.NewSession("raw-token-value", Instant.now().plusSeconds(3600))));

        MvcResult r = mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"google-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ann"))
                .andReturn();

        String cookie = r.getResponse().getHeader("Set-Cookie");
        assertTrue(cookie.startsWith("session=raw-token-value"), cookie);
        assertTrue(cookie.contains("HttpOnly"), "JavaScript must not be able to read the session: " + cookie);
        assertTrue(cookie.contains("SameSite=Lax"), cookie);
        assertTrue(cookie.contains("Path=/"), cookie);
        assertTrue(cookie.contains("Max-Age="), cookie);
        // the token is in the cookie, never in the JSON body
        assertTrue(!r.getResponse().getContentAsString().contains("raw-token-value"));
    }

    @Test
    void googleLoginWithABadTokenIs401AndSetsNoCookie() throws Exception {
        when(auth.loginWithGoogle("forged")).thenThrow(new UnauthorizedException("Invalid Google token"));
        mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"forged\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.error").value("Invalid Google token"));
    }

    @Test
    void googleLoginWhenNotConfiguredIs503() throws Exception {
        when(auth.loginWithGoogle("tok")).thenThrow(new ServiceUnavailableException("Google sign-in is not configured"));
        mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"tok\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Google sign-in is not configured"));
    }

    @Test
    void googleLoginWithoutACredentialIs400() throws Exception {
        for (String body : new String[]{"{}", "{\"credential\":\"\"}", "{\"credential\":\"  \"}", "not json"}) {
            mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(auth, never()).loginWithGoogle(anyString());
    }

    // ------------------------------------------------------------ logout

    @Test
    void logoutEndsTheSessionAndClearsTheCookie() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/logout").cookie(GOOD).header("X-Requested-With", "cards"))
                .andExpect(status().isNoContent()).andReturn();
        verify(auth).logout("good-token");
        String cookie = r.getResponse().getHeader("Set-Cookie");
        assertTrue(cookie.startsWith("session=;"), cookie);
        assertTrue(cookie.contains("Max-Age=0") && cookie.contains("HttpOnly"), cookie);
    }

    @Test
    void logoutWithACookieNeedsTheHeader() throws Exception {
        mvc.perform(post("/api/auth/logout").cookie(GOOD)).andExpect(status().isForbidden());
        verify(auth, never()).logout(anyString());
    }

    @Test
    void logoutWorksForBearerClients() throws Exception {
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer good-token")).andExpect(status().isNoContent());
        verify(auth).logout("good-token");
    }

    @Test
    void logoutWhenSignedOutIsHarmless() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
        verify(auth, never()).logout(anyString());
    }

    // ------------------------------------------------------------ stage 1 stays additive

    // ------------------------------------------------------------ stage 2: games need a signed-in player

    private static final UUID DECK_ID = UUID.randomUUID();

    private static GameDetails details(String code) {
        Game g = Game.builder().id(UUID.randomUUID()).joinCode(code).deckId(DECK_ID).hostId(ANN.id()).maxPlayers(4).maxRounds(500).chooseThreshold(3).clashCallSeconds(5).status(GameStatus.WAITING).build();
        Deck d = Deck.builder().id(DECK_ID).name("Wrestling").visibility(Visibility.PUBLIC).stats(List.of()).build();
        return new GameDetails(g, d, List.of(new SeatInfo(1, SeatKind.HUMAN, "ann", null, ANN.id(), null)));
    }

    private static final String CREATE_BODY = "{\"deckId\":\"" + DECK_ID + "\",\"maxPlayers\":4}";

    @Test
    void creatingAGameWithoutSigningInIs401() throws Exception {
        mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Sign in required"));
        verify(games, never()).create(any(), any(), any(), any());
    }

    @Test
    void creatingAGameWithAnExpiredSessionIs401() throws Exception {
        mvc.perform(post("/api/games").cookie(new Cookie("session", "expired-or-unknown")).header("X-Requested-With", "cards")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creatingAGameWithACookieNeedsTheHeader() throws Exception {
        mvc.perform(post("/api/games").cookie(GOOD).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Missing X-Requested-With header"));
        verify(games, never()).create(any(), any(), any(), any());
    }

    @Test
    void theHostIsTheSessionsPlayerAndAClientSuppliedHostIdIsIgnored() throws Exception {
        when(games.create(DECK_ID, 4, ANN, GameOptions.NONE)).thenReturn(details("K7Q2XM"));
        String body = "{\"deckId\":\"" + DECK_ID + "\",\"maxPlayers\":4,\"hostId\":\"" + UUID.randomUUID() + "\"}";   // impersonation attempt
        mvc.perform(post("/api/games").cookie(GOOD).header("X-Requested-With", "cards").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joinCode").value("K7Q2XM"))
                .andExpect(jsonPath("$.joinUrl").value("http://localhost:8081/?join=K7Q2XM"))
                .andExpect(jsonPath("$.seats[0].username").value("ann"));
        verify(games).create(DECK_ID, 4, ANN, GameOptions.NONE);
    }

    @Test
    void theHostMayOverrideTheRuleSettingsAndTheResponseReportsTheSettingsOfTheGame() throws Exception {
        GameOptions wanted = new GameOptions(100, 0, 8, 30);
        when(games.create(DECK_ID, 4, ANN, wanted)).thenReturn(details("K7Q2XM"));
        String body = "{\"deckId\":\"" + DECK_ID + "\",\"maxPlayers\":4,\"options\":{\"maxRounds\":100,\"chooseThreshold\":0,\"clashCallSeconds\":8,\"turnTimeoutSeconds\":30}}";
        mvc.perform(post("/api/games").cookie(GOOD).header("X-Requested-With", "cards").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.options.maxRounds").value(500))
                .andExpect(jsonPath("$.options.chooseThreshold").value(3))
                .andExpect(jsonPath("$.options.clashCallSeconds").value(5))
                .andExpect(jsonPath("$.options.turnTimeoutSeconds").doesNotExist());           // null in the test fixture: no limit
        verify(games).create(DECK_ID, 4, ANN, wanted);
    }

    @Test
    void partialOptionsKeepTheOtherFieldsUnset() throws Exception {
        when(games.create(DECK_ID, 4, ANN, new GameOptions(50, null, null, null))).thenReturn(details("K7Q2XM"));
        String body = "{\"deckId\":\"" + DECK_ID + "\",\"maxPlayers\":4,\"options\":{\"maxRounds\":50}}";
        mvc.perform(post("/api/games").cookie(GOOD).header("X-Requested-With", "cards").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void anOutOfRangeOptionIsAClearBadRequest() throws Exception {
        when(games.create(DECK_ID, 4, ANN, new GameOptions(5, null, null, null)))
                .thenThrow(new com.cards.common.BadRequestException("maxRounds must be between 20 and 5000"));
        String body = "{\"deckId\":\"" + DECK_ID + "\",\"maxPlayers\":4,\"options\":{\"maxRounds\":5}}";
        mvc.perform(post("/api/games").cookie(GOOD).header("X-Requested-With", "cards").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("maxRounds must be between 20 and 5000"));
    }

    @Test
    void aWronglyTypedOptionIsABadRequest() throws Exception {
        String body = "{\"deckId\":\"" + DECK_ID + "\",\"maxPlayers\":4,\"options\":{\"maxRounds\":\"lots\"}}";
        mvc.perform(post("/api/games").cookie(GOOD).header("X-Requested-With", "cards").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request body"));
    }

    @Test
    void aBearerClientCanCreateAGameWithoutTheHeader() throws Exception {
        when(games.create(DECK_ID, 4, ANN, GameOptions.NONE)).thenReturn(details("K7Q2XM"));
        mvc.perform(post("/api/games").header("Authorization", "Bearer good-token").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void joiningWithoutSigningInIs401() throws Exception {
        mvc.perform(post("/api/games/K7Q2XM/join")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Sign in required"));
        verify(live, never()).join(any(), any());
    }

    @Test
    void joiningWithACookieNeedsTheHeader() throws Exception {
        mvc.perform(post("/api/games/K7Q2XM/join").cookie(GOOD)).andExpect(status().isForbidden());
        verify(games, never()).join(any(), any());
    }

    @Test
    void joiningReturnsTheSameBodyAsReadingTheGame() throws Exception {
        when(live.join("K7Q2XM", ANN)).thenReturn(details("K7Q2XM"));
        mvc.perform(post("/api/games/K7Q2XM/join").cookie(GOOD).header("X-Requested-With", "cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinCode").value("K7Q2XM"))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.deck.name").value("Wrestling"))
                .andExpect(jsonPath("$.seats[0].kind").value("HUMAN"));
    }

    @Test
    void fullStartedAndUnknownGamesAreReportedAsErrors() throws Exception {
        when(live.join("FULL11", ANN)).thenThrow(new com.cards.common.ConflictException("Game is full"));
        when(live.join("NOPE12", ANN)).thenThrow(new com.cards.common.NotFoundException("Game NOPE12 not found"));
        when(live.join("NONAME", ANN)).thenThrow(new com.cards.common.BadRequestException("Choose a username first"));
        mvc.perform(post("/api/games/FULL11/join").cookie(GOOD).header("X-Requested-With", "cards"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Game is full"));
        mvc.perform(post("/api/games/NOPE12/join").cookie(GOOD).header("X-Requested-With", "cards"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/games/NONAME/join").cookie(GOOD).header("X-Requested-With", "cards"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Choose a username first"));
    }

    // ------------------------------------------------------------ lobby actions: sign-in, the header rule, host-only answers

    private static final String HDR = "X-Requested-With";

    @Test
    void theLobbyActionsNeedSignInAndTheHeaderForCookies() throws Exception {
        String[] posts = {"/api/games/K7Q2XM/cpu", "/api/games/K7Q2XM/leave", "/api/games/K7Q2XM/start"};
        for (String url : posts) {
            mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"difficulty\":\"easy\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Sign in required"));
            mvc.perform(post(url).cookie(GOOD).contentType(MediaType.APPLICATION_JSON).content("{\"difficulty\":\"easy\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Missing X-Requested-With header"));
        }
        mvc.perform(delete("/api/games/K7Q2XM/seats/2")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/games/K7Q2XM/seats/2").cookie(GOOD)).andExpect(status().isForbidden());
        verify(live, never()).addCpu(any(), any(), any());
        verify(live, never()).leave(any(), any());
        verify(live, never()).start(any(), any());
        verify(live, never()).removeSeat(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void theHostAddsRemovesAndStartsThroughTheLiveService() throws Exception {
        when(live.addCpu("K7Q2XM", ANN, "hard")).thenReturn(details("K7Q2XM"));
        when(live.removeSeat("K7Q2XM", ANN, 2)).thenReturn(details("K7Q2XM"));
        when(live.start("K7Q2XM", ANN)).thenReturn(details("K7Q2XM"));
        when(live.leave("K7Q2XM", ANN)).thenReturn(details("K7Q2XM"));

        mvc.perform(post("/api/games/K7Q2XM/cpu").cookie(GOOD).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON).content("{\"difficulty\":\"hard\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.joinCode").value("K7Q2XM")).andExpect(jsonPath("$.hostSeat").value(1));
        mvc.perform(delete("/api/games/K7Q2XM/seats/2").cookie(GOOD).header(HDR, "cards")).andExpect(status().isOk());
        mvc.perform(post("/api/games/K7Q2XM/start").cookie(GOOD).header(HDR, "cards")).andExpect(status().isOk());
        mvc.perform(post("/api/games/K7Q2XM/leave").cookie(GOOD).header(HDR, "cards")).andExpect(status().isOk());
        verify(live).addCpu("K7Q2XM", ANN, "hard");
        verify(live).removeSeat("K7Q2XM", ANN, 2);
        verify(live).start("K7Q2XM", ANN);
        verify(live).leave("K7Q2XM", ANN);
    }

    @Test
    void bearerClientsCanUseTheLobbyActionsWithoutTheHeader() throws Exception {
        when(live.start("K7Q2XM", ANN)).thenReturn(details("K7Q2XM"));
        mvc.perform(post("/api/games/K7Q2XM/start").header("Authorization", "Bearer good-token")).andExpect(status().isOk());
    }

    @Test
    void theErrorsOfTheLobbyActionsKeepTheirStatusCodes() throws Exception {
        when(live.start("K7Q2XM", ANN)).thenThrow(new com.cards.common.ForbiddenException("Only the host can do that"));
        when(live.addCpu("FULL11", ANN, "easy")).thenThrow(new com.cards.common.ConflictException("Game is full"));
        when(live.addCpu("K7Q2XM", ANN, "genius")).thenThrow(new com.cards.common.BadRequestException("difficulty must be easy, medium or hard"));
        when(live.removeSeat("K7Q2XM", ANN, 9)).thenThrow(new com.cards.common.NotFoundException("Seat 9 is empty"));

        mvc.perform(post("/api/games/K7Q2XM/start").cookie(GOOD).header(HDR, "cards"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Only the host can do that"));
        mvc.perform(post("/api/games/FULL11/cpu").cookie(GOOD).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON).content("{\"difficulty\":\"easy\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Game is full"));
        mvc.perform(post("/api/games/K7Q2XM/cpu").cookie(GOOD).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON).content("{\"difficulty\":\"genius\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("difficulty must be easy, medium or hard"));
        mvc.perform(delete("/api/games/K7Q2XM/seats/9").cookie(GOOD).header(HDR, "cards")).andExpect(status().isNotFound());
    }

    @Test
    void anOverActiveClientGets429() throws Exception {
        org.mockito.Mockito.doThrow(new com.cards.common.TooManyRequestsException("Too many requests, slow down")).when(rateLimiter).check(ANN.id());
        mvc.perform(post("/api/games/K7Q2XM/start").cookie(GOOD).header(HDR, "cards"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("Too many requests, slow down"));
        verify(live, never()).start(any(), any());
    }

    @Test
    void readingAGameStaysPublic() throws Exception {
        when(games.get("K7Q2XM")).thenReturn(details("K7Q2XM"));
        mvc.perform(get("/api/games/K7Q2XM")).andExpect(status().isOk()).andExpect(jsonPath("$.deck.name").value("Wrestling"));
    }

    @Test
    void theAnonymousPlayerEndpointIsGone() throws Exception {
        mvc.perform(post("/api/players").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ann\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ CORS with credentials

    @Test
    void corsAllowsCredentialsForTheConfiguredOriginOnly() throws Exception {
        mvc.perform(options("/api/me").header("Origin", "http://localhost:8081").header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "content-type,x-requested-with"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        mvc.perform(options("/api/me").header("Origin", "http://evil.example").header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void corsNeverAnswersWithAWildcard() throws Exception {
        mvc.perform(get("/api/me").cookie(GOOD).header("Origin", "http://localhost:8081"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    }
}
