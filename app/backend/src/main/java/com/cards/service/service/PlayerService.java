package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.Player;
import com.cards.service.db.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PlayerService {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{1,8}$");

    private final PlayerRepository players;

    /** Trims + lowercases, then requires 1-8 characters of [a-z0-9_]. 409 when taken. */
    public Player create(String rawUsername) {
        String username = rawUsername == null ? "" : rawUsername.trim().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(username).matches()) {
            throw new BadRequestException("Username must be 1-8 characters: letters, digits or underscore");
        }
        return players.insertIfAbsent(username).orElseThrow(() -> new ConflictException("Username already taken"));
    }
}
