package com.cards.service.controller;

import com.cards.common.Deck;
import com.cards.common.Player;
import com.cards.service.controller.dto.CardResponse;
import com.cards.service.controller.dto.DeckResponse;
import com.cards.service.service.DeckService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/decks")
@RequiredArgsConstructor
public class DeckController {

    private final DeckService decks;

    @GetMapping
    public List<DeckResponse> list(@AuthenticationPrincipal Player me) {
        return decks.listFor(me).stream().map(DeckResponse::from).toList();
    }

    @GetMapping("/{id}")
    public DeckResponse get(@PathVariable UUID id, @AuthenticationPrincipal Player me) {
        return DeckResponse.from(decks.get(id, me));
    }

    @GetMapping("/{id}/cards")
    public List<CardResponse> cards(@PathVariable UUID id, @AuthenticationPrincipal Player me) {
        Deck deck = decks.get(id, me);
        return decks.cardsOf(deck).stream().map(c -> CardResponse.from(c, deck)).toList();
    }
}
