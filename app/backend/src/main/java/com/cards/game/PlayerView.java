package com.cards.game;

import com.cards.common.Card;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything one seat is allowed to know (RULES.md §8): their own hand, how many cards every seat holds, the pot size,
 * whose turn it is, the phase and its deadline. It never contains another seat's cards.
 *
 * <p>The card a seat is about to play is {@code committed} once it has been put in play (the caller's top card at the
 * start of a round, or after CHOOSE); until then it is the first card of {@code ownHand}.
 *
 * @param ownHand   the seat's top card (the next one, once a card is in play), or the WHOLE hand in order while CHOOSE applies
 * @param committed the card this seat has already put in play this round (visible to them only), else null
 * @param ownPile   the seat's own cards in the order they will be played from the NEXT turn on (first = next up); excludes the card
 *                  in play this round, and excludes nothing while the seat may still CHOOSE
 * @param handSizes cards per seat, including a card already put in play
 */
public record PlayerView(int seat, Phase phase, int round, int callerSeat, String stat, int clashLevel, int potSize,
                         Instant deadline, List<Integer> contenders, Map<Integer, Integer> handSizes,
                         List<Card> ownHand, Card committed, boolean canChoose, boolean canCall, List<Card> ownPile) {
}
