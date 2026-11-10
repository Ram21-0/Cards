package com.cards.service.controller;

import com.cards.config.SessionCookies;
import com.cards.service.controller.dto.MeResponse;
import com.cards.service.controller.dto.UsernameRequest;
import com.cards.service.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign in as any username without Google — for local development and tests ONLY.
 * The bean exists only when app.dev-login.enabled=true, which resources/local/application.yml sets and
 * production never does; elsewhere this endpoint simply does not exist (404).
 */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(name = "app.dev-login.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevLoginController {

    private final AuthService auth;
    private final SessionCookies cookies;

    @PostMapping("/login")
    public ResponseEntity<MeResponse> login(@RequestBody UsernameRequest request) {
        AuthService.Login login = auth.devLogin(request.username());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.create(login.session().token(), login.session().expiresAt()))
                .body(MeResponse.from(login.player()));
    }
}
