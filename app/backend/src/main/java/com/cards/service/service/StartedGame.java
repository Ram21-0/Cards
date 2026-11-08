package com.cards.service.service;

import com.cards.game.GameEngine;

/** A game that has just been started: its (renumbered) seats and the freshly created rules engine. */
public record StartedGame(GameDetails details, GameEngine engine) {
}
