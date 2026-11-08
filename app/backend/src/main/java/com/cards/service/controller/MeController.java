package com.cards.service.controller;

import com.cards.common.Player;
import com.cards.config.SessionCookies;
import com.cards.service.controller.dto.MeResponse;
import com.cards.service.controller.dto.UsernameRequest;
import com.cards.service.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in player. All of these need a session (401 otherwise, see SecurityConfig). */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AuthService auth;
    private final SessionCookies cookies;

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal Player me) {
        return MeResponse.from(me);
    }

    @PutMapping
    public MeResponse setUsername(@AuthenticationPrincipal Player me, @RequestBody UsernameRequest request) {
        return MeResponse.from(auth.setUsername(me.id(), request.username()));
    }

    /** Deletes the account: all sessions end, personal data is wiped. */
    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Player me) {
        auth.deleteAccount(me.id());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }
}
