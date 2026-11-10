package com.cards.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.cards.service.service.SessionService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /config.js tells the bundled frontend: same origin (empty apiBase) + the Google client id from the setting. */
class RuntimeConfigControllerTest {

    @Nested
    @WebMvcTest(controllers = RuntimeConfigController.class)
    @Import({SecurityConfig.class, SessionCookies.class, ClockConfig.class})
    @TestPropertySource(properties = "app.google-client-id=abc-123.apps.googleusercontent.com")
    class WithClientId {
        @Autowired MockMvc mvc;
        @MockitoBean SessionService sessions;

        @Test
        void servesJavaScriptWithTheSettingAndNoApiBase() throws Exception {
            mvc.perform(get("/config.js"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/javascript")))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")))
                    .andExpect(content().string("window.CARDS_CONFIG = {\"apiBase\":\"\",\"googleClientId\":\"abc-123.apps.googleusercontent.com\"};\n"));
        }
    }

    @Nested
    @WebMvcTest(controllers = RuntimeConfigController.class)
    @Import({SecurityConfig.class, SessionCookies.class, ClockConfig.class})
    @TestPropertySource(properties = "app.google-client-id=")
    class WithoutClientId {
        @Autowired MockMvc mvc;
        @MockitoBean SessionService sessions;

        @Test
        void anEmptySettingGivesAnEmptyId() throws Exception {
            mvc.perform(get("/config.js"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("window.CARDS_CONFIG = {\"apiBase\":\"\",\"googleClientId\":\"\"};\n"));
        }
    }

    @Nested
    @WebMvcTest(controllers = RuntimeConfigController.class)
    @Import({SecurityConfig.class, SessionCookies.class, ClockConfig.class})
    @TestPropertySource(properties = "app.google-client-id=evil\"; alert(1); //")
    class WithHostileValue {
        @Autowired MockMvc mvc;
        @MockitoBean SessionService sessions;

        @Test
        void theValueIsEscapedSoItCannotBreakOutOfTheString() throws Exception {
            mvc.perform(get("/config.js"))
                    .andExpect(content().string("window.CARDS_CONFIG = {\"apiBase\":\"\",\"googleClientId\":\"evil\\\"; alert(1); //\"};\n"));
        }
    }
}
