package com.cards.live;

import com.cards.common.SeatInfo;
import com.cards.game.PlayerView;
import com.cards.service.controller.dto.CardResponse;
import com.cards.service.controller.dto.GameResponse;
import com.cards.service.service.GameDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds the JSON text messages sent over the WebSocket (see LIVE.md for the formats). */
@Component
public class LiveMessages {

    private final ObjectMapper json;
    private final Clock clock;
    private final String frontendBaseUrl;

    public LiveMessages(ObjectMapper json, Clock clock, @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.json = json;
        this.clock = clock;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /** The lobby (or finished/abandoned game) as this player sees it: the game body plus {@code mySeat}. */
    public String lobby(GameDetails details, UUID playerId) {
        return write(game("lobby", details, playerId));
    }

    /** The game has just started: the game body (seats are now numbered 1..n). A {@code view} follows. */
    public String started(GameDetails details, UUID playerId) {
        return write(game("started", details, playerId));
    }

    public String pong() {
        return write(Map.of("type", "pong", "serverTime", clock.instant()));
    }

    public String error(String message) {
        return write(Map.of("type", "error", "error", message));
    }

    /** The personalised view of one seat. */
    public String view(LiveGame game, int seat) {
        PlayerView v = game.view(seat);
        return write(new ViewMessage("view", clock.instant(), v.seat(), v.phase().name(), v.round(), v.callerSeat(), v.stat(),
                v.clashLevel(), v.potSize(), v.deadline(), v.contenders(), v.handSizes(),
                v.ownHand().stream().map(c -> CardResponse.from(c, game.deck())).toList(),
                v.committed() == null ? null : CardResponse.from(v.committed(), game.deck()),
                v.canChoose(), v.canCall(), game.placements()));
    }

    private Map<String, Object> game(String type, GameDetails details, UUID playerId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("serverTime", clock.instant());
        message.put("mySeat", seatOf(details.seats(), playerId));
        message.put("game", GameResponse.from(details, frontendBaseUrl));
        return message;
    }

    private static Integer seatOf(List<SeatInfo> seats, UUID playerId) {
        return seats.stream().filter(s -> playerId.equals(s.playerId())).map(SeatInfo::seat).findFirst().orElse(null);
    }

    String write(Object message) {
        try {
            return json.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise a live message", e);
        }
    }
}
