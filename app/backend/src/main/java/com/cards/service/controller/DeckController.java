package com.cards.service.controller;

import com.cards.service.controller.dto.CardResponse;
import com.cards.service.controller.dto.DeckResponse;
import com.cards.service.service.DeckService;
import lombok.RequiredArgsConstructor;
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
    public List<DeckResponse> list() {
        return decks.listPublic().stream().map(DeckResponse::from).toList();
    }

    @GetMapping("/{id}")
    public DeckResponse get(@PathVariable UUID id) {
        return DeckResponse.from(decks.get(id));
    }

    @GetMapping("/{id}/cards")
    public List<CardResponse> cards(@PathVariable UUID id) {
        return decks.cardsOf(id).stream().map(CardResponse::from).toList();
    }
}
