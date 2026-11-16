package com.cards.service.controller.dto;

/** {"name": "…", "description": "…"}; a field left out stays as it is. */
public record RenameDeckBody(String name, String description) {
}
