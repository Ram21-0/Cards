package com.cards.service.controller;

import com.cards.admin.AdminAccess;
import com.cards.common.Player;
import com.cards.config.ClockConfig;
import com.cards.config.SecurityConfig;
import com.cards.config.SessionCookies;
import com.cards.live.RateLimiter;
import com.cards.service.service.DeckImportService;
import com.cards.service.service.DeckLibraryService;
import com.cards.service.service.DeckRequestService;
import com.cards.service.service.DeckService;
import com.cards.service.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call the deck management endpoints, and the multipart error answers. */
@WebMvcTest(controllers = {DeckManageController.class, ImageController.class})
@Import({SecurityConfig.class, SessionCookies.class, ClockConfig.class})
class DeckManageWebTest {

    private static final Player ANN = Player.builder().id(UUID.randomUUID()).username("ann").createdAt(Instant.now()).build();
    private static final Cookie COOKIE = new Cookie("session", "ann-token");
    private static final String ID = UUID.randomUUID().toString();

    @Autowired MockMvc mvc;
    @MockitoBean SessionService sessions;
    @MockitoBean AdminAccess adminAccess;
    @MockitoBean DeckImportService imports;
    @MockitoBean DeckLibraryService library;
    @MockitoBean DeckRequestService requests;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean com.cards.image.ImageStore imageStore;

    @BeforeEach
    void known() {
        when(sessions.authenticate("ann-token")).thenReturn(Optional.of(ANN));
        when(library.mine(ANN)).thenReturn(List.of());
    }

    private List<MockHttpServletRequestBuilder> stateChanging() {
        return List.of(post("/api/decks/import"), org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/decks/" + ID + "/import"),
                patch("/api/decks/" + ID), delete("/api/decks/" + ID), post("/api/decks/" + ID + "/copy"), post("/api/decks/" + ID + "/request-global"),
                delete("/api/deck-requests/" + ID));
    }

    @Test
    void everyStateChangingEndpointNeedsSignInAndTheHeaderForCookies() throws Exception {
        for (MockHttpServletRequestBuilder request : stateChanging()) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
            mvc.perform(request.cookie(COOKIE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Missing X-Requested-With header"));
        }
        mvc.perform(get("/api/decks/mine")).andExpect(status().isUnauthorized());
        verify(library, never()).delete(ANN, UUID.fromString(ID));
        verify(requests, never()).createPromotion(ANN, UUID.fromString(ID));
    }

    @Test
    void myDecksAndTheTemplateAndImages() throws Exception {
        mvc.perform(get("/api/decks/mine").cookie(COOKIE)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/decks/import/template")).andExpect(status().isOk()).andExpect(jsonPath("$.mapping.stats.length()").value(6))
                .andExpect(jsonPath("$.csv").value(org.hamcrest.Matchers.startsWith("name,image,brand,rank,fight,chest,biceps,height,weight")));
        mvc.perform(get("/api/images/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void aMissingPartIsA400WithTheUsualBody() throws Exception {
        mvc.perform(multipart("/api/decks/import").file(new MockMultipartFile("csv", "cards.csv", "text/csv", "name\n".getBytes())).cookie(COOKIE).header("X-Requested-With", "cards"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("mapping is required"));
        mvc.perform(multipart("/api/decks/import").param("mapping", "{}").cookie(COOKIE).header("X-Requested-With", "cards"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("csv is required"));
        mvc.perform(post("/api/decks/import").contentType(MediaType.APPLICATION_JSON).content("{}").cookie(COOKIE).header("X-Requested-With", "cards"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Send the upload as multipart/form-data"));
    }
}
