package com.cards.service.controller;

import com.cards.admin.AdminAccess;
import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.DeckRequest;
import com.cards.common.DeckRequestStatus;
import com.cards.common.Player;
import com.cards.common.TooManyRequestsException;
import com.cards.config.ClockConfig;
import com.cards.config.SecurityConfig;
import com.cards.config.SessionCookies;
import com.cards.live.RateLimiter;
import com.cards.service.service.DeckRequestService;
import com.cards.service.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Security of the deck request endpoints: sign-in, the admin allow-list, and the X-Requested-With rule. */
@WebMvcTest(controllers = {DeckRequestController.class, AdminDeckRequestController.class})
@Import({SecurityConfig.class, SessionCookies.class, ClockConfig.class, AdminAccess.class})
@TestPropertySource(properties = "app.admin.emails=boss@example.com")
class DeckRequestWebTest {

    private static final String HDR = "X-Requested-With";
    private static final Player ANN = Player.builder().id(UUID.randomUUID()).username("ann").email("ann@example.com").createdAt(Instant.now()).build();
    private static final Player BOSS = Player.builder().id(UUID.randomUUID()).username("boss").email("Boss@Example.com").createdAt(Instant.now()).build();
    private static final Cookie ANN_COOKIE = new Cookie("session", "ann-token");
    private static final Cookie BOSS_COOKIE = new Cookie("session", "boss-token");
    private static final UUID RID = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean SessionService sessions;
    @MockitoBean DeckRequestService requests;
    @MockitoBean RateLimiter rateLimiter;

    private final DeckRequest sample = DeckRequest.builder().id(RID).type(com.cards.common.DeckRequestType.CATEGORY).requesterId(ANN.id()).requesterUsername("ann").category("Cricket legends")
            .description("d").names(List.of("A", "B", "C")).status(DeckRequestStatus.PENDING).createdAt(Instant.parse("2026-11-20T10:00:00Z")).build();

    @BeforeEach
    void sessionsKnown() {
        when(sessions.authenticate("ann-token")).thenReturn(Optional.of(ANN));
        when(sessions.authenticate("boss-token")).thenReturn(Optional.of(BOSS));
    }

    // ------------------------------------------------------------ users

    @Test
    void anonymousUsersCannotRequestOrList() throws Exception {
        mvc.perform(post("/api/deck-requests").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Sign in required"));
        mvc.perform(get("/api/deck-requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/deck-requests/" + RID)).andExpect(status().isUnauthorized());
        verify(requests, never()).create(any(), any(), any(), any());
    }

    @Test
    void creatingNeedsTheHeaderWhenSignedInByCookie() throws Exception {
        mvc.perform(post("/api/deck-requests").cookie(ANN_COOKIE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Missing X-Requested-With header"));
        verify(requests, never()).create(any(), any(), any(), any());
    }

    @Test
    void aSignedInUserCreatesAndListsTheirRequests() throws Exception {
        when(requests.create(eq(ANN), eq("Cricket legends"), eq("d"), eq(List.of("A", "B", "C")))).thenReturn(sample);
        when(requests.mine(ANN)).thenReturn(List.of(sample));
        when(requests.mine(ANN, RID)).thenReturn(sample);

        mvc.perform(post("/api/deck-requests").cookie(ANN_COOKIE).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"Cricket legends\",\"description\":\"d\",\"names\":[\"A\",\"B\",\"C\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(RID.toString())).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.live").value(false)).andExpect(jsonPath("$.names.length()").value(3))
                .andExpect(jsonPath("$.requester").doesNotExist());
        mvc.perform(get("/api/deck-requests").cookie(ANN_COOKIE)).andExpect(status().isOk()).andExpect(jsonPath("$[0].category").value("Cricket legends"));
        mvc.perform(get("/api/deck-requests/" + RID).cookie(ANN_COOKIE)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(RID.toString()));
        mvc.perform(get("/api/deck-requests").header("Authorization", "Bearer ann-token")).andExpect(status().isOk());
    }

    @Test
    void validationAndLimitErrorsKeepTheUsualBody() throws Exception {
        when(requests.create(any(), any(), any(), any())).thenThrow(new BadRequestException("Suggest 3 to 60 different names"));
        mvc.perform(post("/api/deck-requests").cookie(ANN_COOKIE).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"abc\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Suggest 3 to 60 different names"));
        org.mockito.Mockito.doThrow(new ConflictException("You already have 3 pending requests; wait for a decision")).when(requests).create(any(), any(), any(), any());
        mvc.perform(post("/api/deck-requests").cookie(ANN_COOKIE).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        org.mockito.Mockito.doThrow(new TooManyRequestsException("You can send 5 requests per day; try again tomorrow")).when(requests).create(any(), any(), any(), any());
        mvc.perform(post("/api/deck-requests").cookie(ANN_COOKIE).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("You can send 5 requests per day; try again tomorrow"));
    }

    // ------------------------------------------------------------ admin

    @Test
    void everyAdminEndpointIs401SignedOutAnd403ForANonAdmin() throws Exception {
        String base = "/api/admin/deck-requests";
        String id = base + "/" + RID;
        for (var request : List.of(get(base), get(id), patch(id), post(id + "/approve"), post(id + "/reject"), get(base + "/anything/else"))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        }
        for (var request : List.of(get(base), get(id), patch(id), post(id + "/approve"), post(id + "/reject"))) {
            mvc.perform(request.cookie(ANN_COOKIE).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Forbidden"));
            mvc.perform(request.header("Authorization", "Bearer ann-token").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        verify(requests, never()).list(any());
        verify(requests, never()).approve(any(), any(), any(), any());
        verify(requests, never()).reject(any(), any(), any());
        verify(requests, never()).edit(any(), any(), any(), any());
    }

    @Test
    void anAdminListsReadsEditsApprovesAndRejects() throws Exception {
        DeckRequest approved = sample.toBuilder().status(DeckRequestStatus.APPROVED_GLOBAL).resultingDeckId(UUID.randomUUID()).decidedBy(BOSS.id()).build();
        when(requests.list("PENDING")).thenReturn(List.of(sample));
        when(requests.get(RID)).thenReturn(sample);
        when(requests.edit(eq(RID), eq(null), eq(null), eq(List.of("X", "Y", "Z")))).thenReturn(sample);
        when(requests.approve(eq(RID), eq(BOSS), eq("PUBLIC"), eq("ok"))).thenReturn(approved);
        when(requests.reject(eq(RID), eq(BOSS), eq("no"))).thenReturn(sample.toBuilder().status(DeckRequestStatus.REJECTED).adminNote("no").build());

        mvc.perform(get("/api/admin/deck-requests?status=PENDING").cookie(BOSS_COOKIE)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requester.username").value("ann")).andExpect(jsonPath("$[0].status").value("PENDING"));
        mvc.perform(get("/api/admin/deck-requests/" + RID).cookie(BOSS_COOKIE)).andExpect(status().isOk()).andExpect(jsonPath("$.names.length()").value(3));
        mvc.perform(patch("/api/admin/deck-requests/" + RID).cookie(BOSS_COOKIE).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON).content("{\"names\":[\"X\",\"Y\",\"Z\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/deck-requests/" + RID + "/approve").cookie(BOSS_COOKIE).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\",\"note\":\"ok\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED_GLOBAL")).andExpect(jsonPath("$.decidedBy").value(BOSS.id().toString()))
                .andExpect(jsonPath("$.live").value(false));
        mvc.perform(post("/api/admin/deck-requests/" + RID + "/reject").cookie(BOSS_COOKIE).header(HDR, "cards").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"no\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.adminNote").value("no"));
    }

    @Test
    void theAdminAllowListIsCaseInsensitiveAndWorksForBearerToo() throws Exception {
        when(requests.list(null)).thenReturn(List.of());
        mvc.perform(get("/api/admin/deck-requests").header("Authorization", "Bearer boss-token")).andExpect(status().isOk());
    }

    @Test
    void adminStateChangesNeedTheHeaderWhenSignedInByCookie() throws Exception {
        for (var request : List.of(patch("/api/admin/deck-requests/" + RID), post("/api/admin/deck-requests/" + RID + "/approve"), post("/api/admin/deck-requests/" + RID + "/reject"))) {
            mvc.perform(request.cookie(BOSS_COOKIE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Missing X-Requested-With header"));
        }
        verify(requests, never()).approve(any(), any(), any(), any());
    }

    @Test
    void removingAnAddressFromTheAllowListRevokesAccessAtOnce() throws Exception {
        // the principal is looked up on every request; an account whose email is no longer listed is simply not an admin
        Player former = Player.builder().id(UUID.randomUUID()).username("ex").email("ex-admin@example.com").build();
        when(sessions.authenticate("ex-token")).thenReturn(Optional.of(former));
        mvc.perform(get("/api/admin/deck-requests").header("Authorization", "Bearer ex-token")).andExpect(status().isForbidden());
    }
}
