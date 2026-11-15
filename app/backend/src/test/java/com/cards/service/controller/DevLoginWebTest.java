package com.cards.service.controller;

import com.cards.admin.AdminAccess;
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
        @MockitoBean AdminAccess adminAccess;

        @Test
        void signsInWithoutGoogleAndSetsTheCookie() throws Exception {
            Player ann = Player.builder().id(UUID.randomUUID()).username("ann").build();
            when(auth.devLogin("ann", false)).thenReturn(new AuthService.Login(ann, new SessionService.NewSession("dev-token", Instant.now().plusSeconds(60))));
            MvcResult r = mvc.perform(post("/api/dev/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ann\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("ann")).andReturn();
            assertTrue(r.getResponse().getHeader("Set-Cookie").startsWith("session=dev-token"));
        }

        @Test
        void adminTrueSignsInAsTheDevAdmin() throws Exception {
            Player boss = Player.builder().id(UUID.randomUUID()).username("boss").email(AdminAccess.DEV_ADMIN_EMAIL).build();
            when(auth.devLogin("boss", true)).thenReturn(new AuthService.Login(boss, new SessionService.NewSession("dev-token", Instant.now().plusSeconds(60))));
            when(adminAccess.isAdmin(boss)).thenReturn(true);
            mvc.perform(post("/api/dev/login?admin=true").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"boss\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.isAdmin").value(true));
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
        @MockitoBean AdminAccess adminAccess;

        @Test
        void theEndpointDoesNotExist() throws Exception {
            mvc.perform(post("/api/dev/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ann\"}"))
                    .andExpect(status().isNotFound());
        }
    }
}
