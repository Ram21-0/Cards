package com.cards.service.service;

import com.cards.common.ConflictException;
import com.cards.common.Player;
import com.cards.service.db.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository players;

    /** Anonymous username-only player (goes away when sign-in becomes mandatory). 409 when taken. */
    public Player create(String rawUsername) {
        String username = Usernames.normalize(rawUsername);
        return players.insertIfAbsent(username).orElseThrow(() -> new ConflictException("Username already taken"));
    }
}
