package com.cards.service.controller.dto;

import com.cards.common.Player;

import java.util.UUID;

/** The signed-in player. {@code isAdmin}: their verified email is in the ADMIN_EMAILS allow-list. {@code username} is null until they choose one ({@code needsUsername} says so). */
public record MeResponse(UUID id, String username, String displayName, String avatarUrl, String email, boolean needsUsername,
                         boolean isAdmin) {

    public static MeResponse from(Player p, boolean isAdmin) {
        return new MeResponse(p.id(), p.username(), p.displayName(), p.avatarUrl(), p.email(), p.needsUsername(), isAdmin);
    }
}
