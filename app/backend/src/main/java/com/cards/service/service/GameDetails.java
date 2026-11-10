package com.cards.service.service;

import com.cards.common.Deck;
import com.cards.common.Game;
import com.cards.common.SeatInfo;

import java.util.List;

/** A game together with its deck and seats (what a lobby screen needs). */
public record GameDetails(Game game, Deck deck, List<SeatInfo> seats) {
}
