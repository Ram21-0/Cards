package com.cards.service.controller;

import com.cards.common.Player;
import com.cards.live.RateLimiter;
import com.cards.service.controller.dto.AdminDeckRequestResponse;
import com.cards.service.controller.dto.ApproveDeckRequestBody;
import com.cards.service.controller.dto.EditDeckRequestBody;
import com.cards.service.controller.dto.RejectDeckRequestBody;
import com.cards.service.service.DeckRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin's side of deck requests. Everything under /api/admin/** is limited to admins by SecurityConfig (401 signed out, 403 not
 * an admin), so these methods can rely on {@code me} being an admin.
 */
@RestController
@RequestMapping("/api/admin/deck-requests")
@RequiredArgsConstructor
public class AdminDeckRequestController {

    private final DeckRequestService requests;
    private final RateLimiter rateLimiter;

    @GetMapping
    public List<AdminDeckRequestResponse> list(@AuthenticationPrincipal Player me, @RequestParam(required = false) String status) {
        rateLimiter.check(me.id());
        return requests.list(status).stream().map(AdminDeckRequestResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AdminDeckRequestResponse get(@AuthenticationPrincipal Player me, @PathVariable UUID id) {
        rateLimiter.check(me.id());
        return AdminDeckRequestResponse.from(requests.get(id));
    }

    @PatchMapping("/{id}")
    public AdminDeckRequestResponse edit(@AuthenticationPrincipal Player me, @PathVariable UUID id, @RequestBody EditDeckRequestBody body) {
        rateLimiter.check(me.id());
        return AdminDeckRequestResponse.from(requests.edit(id, body.category(), body.description(), body.names()));
    }

    @PostMapping("/{id}/approve")
    public AdminDeckRequestResponse approve(@AuthenticationPrincipal Player me, @PathVariable UUID id, @RequestBody ApproveDeckRequestBody body) {
        rateLimiter.check(me.id());
        return AdminDeckRequestResponse.from(requests.approve(id, me, body.visibility(), body.note()));
    }

    @PostMapping("/{id}/reject")
    public AdminDeckRequestResponse reject(@AuthenticationPrincipal Player me, @PathVariable UUID id, @RequestBody RejectDeckRequestBody body) {
        rateLimiter.check(me.id());
        return AdminDeckRequestResponse.from(requests.reject(id, me, body.note()));
    }
}
