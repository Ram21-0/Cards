package com.cards.service.controller.dto;

import com.cards.common.Player;

import java.util.UUID;

/** The signed-in player. {@code username} is null until they choose one ({@code needsUsername} says so). */
public record MeResponse(UUID id, String username, String displayName, String avatarUrl, String email, boolean needsUsername) {

    public static MeResponse from(Player p) {
        return new MeResponse(p.id(), p.username(), p.displayName(), p.avatarUrl(), p.email(), p.needsUsername());
    }
}
