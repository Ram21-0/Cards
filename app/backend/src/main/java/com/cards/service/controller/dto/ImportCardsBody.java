package com.cards.service.controller.dto;

import com.cards.deckimport.DeckCardImport;

import java.util.List;

/** JSON form of the stat import: {"cards": [{"name": "…", "rank": 1, "fight": 44, "chest": 38.5, "biceps": 15, "height": 73, "weight": 240, "brand": "raw"}, …]}. */
public record ImportCardsBody(List<DeckCardImport> cards) {
}
