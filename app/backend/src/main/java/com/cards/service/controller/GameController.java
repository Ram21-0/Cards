package com.cards.service.controller;

import com.cards.common.Player;
import com.cards.live.LiveGameService;
import com.cards.live.RateLimiter;
import com.cards.service.controller.dto.AddCpuRequest;
import com.cards.service.controller.dto.CreateGameRequest;
import com.cards.service.controller.dto.GameResponse;
import com.cards.service.service.GameService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lobby: create a game, join it, add/remove CPUs, leave, start (all need sign-in; the host-only ones answer 403 to others)
 * and read it by its join code (public). Every response is the same game body. See LIVE.md.
 */
@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService games;
    private final LiveGameService live;
    private final RateLimiter rateLimiter;
    private final String frontendBaseUrl;

    public GameController(GameService games, LiveGameService live, RateLimiter rateLimiter,
                          @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.games = games;
        this.live = live;
        this.rateLimiter = rateLimiter;
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
        rateLimiter.check(me.id());
        return GameResponse.from(live.join(joinCode, me), frontendBaseUrl);
    }

    /** Host: adds a CPU seat ({"difficulty": "easy" | "medium" | "hard"}). */
    @PostMapping("/{joinCode}/cpu")
    public GameResponse addCpu(@AuthenticationPrincipal Player me, @PathVariable String joinCode, @RequestBody AddCpuRequest request) {
        rateLimiter.check(me.id());
        return GameResponse.from(live.addCpu(joinCode, me, request.difficulty()), frontendBaseUrl);
    }

    /** Host: removes a CPU seat. */
    @DeleteMapping("/{joinCode}/seats/{seat}")
    public GameResponse removeSeat(@AuthenticationPrincipal Player me, @PathVariable String joinCode, @PathVariable int seat) {
        rateLimiter.check(me.id());
        return GameResponse.from(live.removeSeat(joinCode, me, seat), frontendBaseUrl);
    }

    /** A human leaves the lobby (before the game starts). */
    @PostMapping("/{joinCode}/leave")
    public GameResponse leave(@AuthenticationPrincipal Player me, @PathVariable String joinCode) {
        rateLimiter.check(me.id());
        return GameResponse.from(live.leave(joinCode, me), frontendBaseUrl);
    }

    /** Host: starts the game (needs at least 2 seats). */
    @PostMapping("/{joinCode}/start")
    public GameResponse start(@AuthenticationPrincipal Player me, @PathVariable String joinCode) {
        rateLimiter.check(me.id());
        return GameResponse.from(live.start(joinCode, me), frontendBaseUrl);
    }

    @GetMapping("/{joinCode}")
    public GameResponse get(@PathVariable String joinCode) {
        return GameResponse.from(games.get(joinCode), frontendBaseUrl);
    }
}
