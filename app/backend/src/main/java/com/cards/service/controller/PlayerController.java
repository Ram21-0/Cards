package com.cards.service.controller;

import com.cards.service.controller.dto.CreatePlayerRequest;
import com.cards.service.controller.dto.PlayerResponse;
import com.cards.service.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/players")
@RequiredArgsConstructor
public class PlayerController {

    private final PlayerService players;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlayerResponse create(@RequestBody CreatePlayerRequest request) {
        return PlayerResponse.from(players.create(request.username()));
    }
}
