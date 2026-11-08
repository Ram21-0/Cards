package com.cards.service.controller.dto;

import com.cards.common.SeatInfo;
import com.cards.service.service.GameDetails;

import java.util.List;
import java.util.UUID;

/** A game for the lobby screen: shareable code + link, deck, and who sits where. */
public record GameResponse(UUID id, String joinCode, String joinUrl, String status, int maxPlayers,
                           DeckRef deck, List<Seat> seats, Options options) {

    /** The rule settings of this game (RULES.md §1); turnTimeoutSeconds is null when there is no limit. */
    public record Options(int maxRounds, int chooseThreshold, int clashCallSeconds, Integer turnTimeoutSeconds) {
    }

    public record DeckRef(UUID id, String name) {
    }

    public record Seat(int seat, String kind, String username, String difficulty) {

        static Seat from(SeatInfo s) {
            return new Seat(s.seat(), s.kind().name(), s.username(), s.difficulty() == null ? null : s.difficulty().name());
        }
    }

    /** The join link is built here because the frontend's address differs per environment. */
    public static GameResponse from(GameDetails d, String frontendBaseUrl) {
        String base = frontendBaseUrl.endsWith("/") ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
        return new GameResponse(d.game().id(), d.game().joinCode(), base + "/?join=" + d.game().joinCode(),
                d.game().status().name(), d.game().maxPlayers(),
                new DeckRef(d.deck().id(), d.deck().name()),
                d.seats().stream().map(Seat::from).toList(),
                new Options(d.game().maxRounds(), d.game().chooseThreshold(), d.game().clashCallSeconds(), d.game().turnTimeoutSeconds()));
    }
}
