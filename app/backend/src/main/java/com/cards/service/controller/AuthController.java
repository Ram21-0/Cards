package com.cards.service.controller;

import com.cards.common.BadRequestException;
import com.cards.config.SessionCookies;
import com.cards.service.controller.dto.CredentialRequest;
import com.cards.service.controller.dto.MeResponse;
import com.cards.service.controller.dto.NonceResponse;
import com.cards.service.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;
    private final SessionCookies cookies;

    /** Step 1 of Google sign-in: a one-time nonce to hand to Google (replay protection). */
    @PostMapping("/nonce")
    public NonceResponse nonce() {
        return new NonceResponse(auth.newNonce());
    }

    /** Step 2: the browser sends Google's ID token; we verify it and start a session (cookie + player in the body). */
    @PostMapping("/google")
    public ResponseEntity<MeResponse> google(@RequestBody CredentialRequest request) {
        if (request.credential() == null || request.credential().isBlank()) {
            throw new BadRequestException("credential is required");
        }
        AuthService.Login login = auth.loginWithGoogle(request.credential());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.create(login.session().token(), login.session().expiresAt()))
                .body(MeResponse.from(login.player()));
    }

    /** Ends the session and clears the cookie. Idempotent: signed-out callers get 204 too. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        SessionCookies.Presented presented = cookies.extract(request);
        if (presented != null) auth.logout(presented.token());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }
}
