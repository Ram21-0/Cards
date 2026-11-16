package com.cards.service.controller;

import com.cards.common.Player;
import com.cards.live.RateLimiter;
import com.cards.service.controller.dto.CreateDeckRequestBody;
import com.cards.service.controller.dto.DeckRequestResponse;
import com.cards.service.service.DeckRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** A signed-in player asks for a new deck category and sees the status of their own requests. See docs/deck-requests/API.md. */
@RestController
@RequestMapping("/api/deck-requests")
@RequiredArgsConstructor
public class DeckRequestController {

    private final DeckRequestService requests;
    private final RateLimiter rateLimiter;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeckRequestResponse create(@AuthenticationPrincipal Player me, @RequestBody CreateDeckRequestBody body) {
        rateLimiter.check(me.id());
        return DeckRequestResponse.from(requests.create(me, body.category(), body.description(), body.names()));
    }

    /** Withdraws one of my requests that is still pending. */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public org.springframework.http.ResponseEntity<Void> withdraw(@AuthenticationPrincipal Player me, @PathVariable UUID id) {
        rateLimiter.check(me.id());
        requests.withdraw(me, id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<DeckRequestResponse> mine(@AuthenticationPrincipal Player me) {
        return requests.mine(me).stream().map(DeckRequestResponse::from).toList();
    }

    @GetMapping("/{id}")
    public DeckRequestResponse one(@AuthenticationPrincipal Player me, @PathVariable UUID id) {
        return DeckRequestResponse.from(requests.mine(me, id));
    }
}
