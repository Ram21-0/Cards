package com.cards.game;

import com.cards.common.Card;
import com.cards.common.StatDefinition;

import java.util.List;

/** Creates the rules engine for a game that is starting. The production one deals randomly; tests supply known hands. */
@FunctionalInterface
public interface GameEngineFactory {

    GameEngine create(List<Card> deck, List<StatDefinition> stats, List<SeatSpec> seats, GameSettings settings);
}
