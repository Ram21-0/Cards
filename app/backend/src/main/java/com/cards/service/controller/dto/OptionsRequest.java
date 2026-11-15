package com.cards.service.controller.dto;

/** {"resultHoldSeconds": 8}: the host's change to a game option. Only the fields present are changed; more options can be added here later. */
public record OptionsRequest(Integer resultHoldSeconds) {
}
