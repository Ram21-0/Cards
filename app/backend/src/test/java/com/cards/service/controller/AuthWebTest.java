package com.cards.service.controller;

import com.cards.common.Player;
import com.cards.common.ServiceUnavailableException;
import com.cards.common.UnauthorizedException;
import com.cards.config.ClockConfig;
import com.cards.config.SecurityConfig;
import com.cards.config.SessionCookies;
import com.cards.service.service.AuthService;
import com.cards.service.service.PlayerService;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
@WebMvcTest(controllers = {AuthController.class, MeController.class, PlayerController.class})
@Import({SecurityConfig.class, SessionCookies.class, ClockConfig.class})
class AuthWebTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Player ANN = Player.builder().id(ID).username("ann").displayName("Ann Example")
            .email("ann@example.com").avatarUrl("https://pic").createdAt(Instant.now()).build();
    private static final Cookie GOOD = new Cookie("session", "good-token");

    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean SessionService sessions;
    @MockitoBean PlayerService playerService;

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

    @Test
    void theAnonymousPlayerEndpointStillWorksWithoutASession() throws Exception {
        when(playerService.create("ann")).thenReturn(ANN);
        mvc.perform(post("/api/players").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ann\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("ann"));
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
