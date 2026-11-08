package com.cards.service.controller;

import com.cards.common.Player;
import com.cards.service.controller.dto.CreateGameRequest;
import com.cards.service.controller.dto.GameResponse;
import com.cards.service.service.GameService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Lobby: create a game (sign-in required), join it (sign-in required), read it back by its join code (public). */
@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService games;
    private final String frontendBaseUrl;

    public GameController(GameService games, @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.games = games;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GameResponse create(@AuthenticationPrincipal Player me, @RequestBody CreateGameRequest request) {
        return GameResponse.from(games.create(request.deckId(), request.maxPlayers(), me, request.gameOptions()), frontendBaseUrl);
    }

    /** Takes the next free seat; responds with the same body as GET. 409 when full / started; 200 again if already seated. */
    @PostMapping("/{joinCode}/join")
    public GameResponse join(@AuthenticationPrincipal Player me, @PathVariable String joinCode) {
        return GameResponse.from(games.join(joinCode, me), frontendBaseUrl);
    }

    @GetMapping("/{joinCode}")
    public GameResponse get(@PathVariable String joinCode) {
        return GameResponse.from(games.get(joinCode), frontendBaseUrl);
    }
}
