package com.cards.service.controller.dto;

import java.util.List;
import java.util.Map;

/** JSON form of the admin's stat import: {"cards": [{"name": "…", "rank": 1, "fight": 44, "chest": 38.5, "biceps": 15, "height": 73, "weight": 240, "brand": "raw", "image": "https://…"}, …]}. */
public record ImportCardsBody(List<Map<String, Object>> cards) {
}
