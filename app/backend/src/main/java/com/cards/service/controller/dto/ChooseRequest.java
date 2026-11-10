package com.cards.service.controller.dto;

/** {"cardId": 17}: a card id from the player's own hand (see the personal view). */
public record ChooseRequest(Integer cardId) {
}
