package com.cards.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the frontend's {@code config.js} when the frontend is bundled into this application (one origin: the API
 * is at the same address, so {@code apiBase} is empty) and takes the Google client id from the same
 * {@code GOOGLE_CLIENT_ID} setting the verifier uses — so there is a single source of truth and nothing to rebuild.
 * (The bundle build leaves the static config.js out; during local development the frontend on :8081 uses its own file.)
 */
@RestController
public class RuntimeConfigController {

    private final ObjectMapper json;
    private final String googleClientId;

    public RuntimeConfigController(ObjectMapper json, @Value("${app.google-client-id:}") String googleClientId) {
        this.json = json;
        this.googleClientId = googleClientId == null ? "" : googleClientId.trim();
    }

    @GetMapping(value = "/config.js", produces = "application/javascript")
    public ResponseEntity<String> config() throws JsonProcessingException {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("apiBase", "");
        config.put("googleClientId", googleClientId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body("window.CARDS_CONFIG = " + json.writeValueAsString(config) + ";\n");
    }
}
