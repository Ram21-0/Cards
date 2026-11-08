package com.cards.service.controller.dto;

import com.cards.common.Player;

import java.util.UUID;

public record PlayerResponse(UUID id, String username) {

    public static PlayerResponse from(Player p) {
        return new PlayerResponse(p.id(), p.username());
    }
}
