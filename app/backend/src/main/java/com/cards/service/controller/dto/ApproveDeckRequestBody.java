package com.cards.service.controller.dto;

/** {"visibility": "PRIVATE" | "PUBLIC", "note": "optional"} */
public record ApproveDeckRequestBody(String visibility, String note) {
}
