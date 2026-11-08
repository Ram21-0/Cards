package com.cards.service.controller;

import com.cards.common.Player;
import com.cards.config.ClockConfig;
import com.cards.config.SecurityConfig;
import com.cards.config.SessionCookies;
import com.cards.service.service.AuthService;
import com.cards.service.service.SessionService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/dev/login exists only where app.dev-login.enabled=true (the local config), and nowhere else. */
class DevLoginWebTest {

    @Nested
    @WebMvcTest(controllers = DevLoginController.class)
    @Import({SecurityConfig.class, SessionCookies.class, ClockConfig.class})
    @TestPropertySource(properties = "app.dev-login.enabled=true")
    class WhenEnabled {
        @Autowired MockMvc mvc;
        @MockitoBean AuthService auth;
        @MockitoBean SessionService sessions;

        @Test
        void signsInWithoutGoogleAndSetsTheCookie() throws Exception {
            Player ann = Player.builder().id(UUID.randomUUID()).username("ann").build();
            when(auth.devLogin("ann")).thenReturn(new AuthService.Login(ann, new SessionService.NewSession("dev-token", Instant.now().plusSeconds(60))));
            MvcResult r = mvc.perform(post("/api/dev/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ann\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("ann")).andReturn();
            assertTrue(r.getResponse().getHeader("Set-Cookie").startsWith("session=dev-token"));
        }
    }

    @Nested
    @WebMvcTest(controllers = DevLoginController.class)
    @Import({SecurityConfig.class, SessionCookies.class, ClockConfig.class})
    @TestPropertySource(properties = "app.dev-login.enabled=false")
    class WhenDisabled {
        @Autowired MockMvc mvc;
        @MockitoBean AuthService auth;
        @MockitoBean SessionService sessions;

        @Test
        void theEndpointDoesNotExist() throws Exception {
            mvc.perform(post("/api/dev/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ann\"}"))
                    .andExpect(status().isNotFound());
        }
    }
}
