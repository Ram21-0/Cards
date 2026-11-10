package com.cards.live;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The games that are running (IN_PROGRESS) right now, by join code. Held in memory only: see PLAN.md. */
@Component
public class GameRegistry {

    private final Map<String, LiveGame> running = new ConcurrentHashMap<>();

    public void put(LiveGame game) {
        running.put(game.code(), game);
    }

    public Optional<LiveGame> find(String code) {
        return Optional.ofNullable(running.get(code));
    }

    public void remove(String code) {
        running.remove(code);
    }

    public int size() {
        return running.size();
    }
}
