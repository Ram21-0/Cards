package com.cards.service.controller.dto;

import java.util.List;

/** {"category": "Cricket legends", "description": "…", "names": ["Sachin Tendulkar", …]} */
public record CreateDeckRequestBody(String category, String description, List<String> names) {
}
