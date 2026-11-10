package com.cards.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.cards.game.TestKit.DEFAULT;
import static com.cards.game.TestKit.NO_CHOOSE;
import static com.cards.game.TestKit.card;
import static com.cards.game.TestKit.hand;
import static com.cards.game.TestKit.hands;
import static com.cards.game.TestKit.humansEngine;
import static com.cards.game.TestKit.ids;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code PlayerView.ownPile}: my own cards in the order they come up after the card in play this round. */
class GameEngineOwnPileTest {

    @Test
    void theCardInPlayIsLeftOutAndTheRestKeepTheirOrder() {
        // CHOOSE off: top cards are played, so the pile is the hand minus the top card
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(hand(card(11, 5), card(12, 6), card(13, 7)), hand(card(21, 5), card(22, 6))));
        assertEquals(List.of(12, 13), ids(e.view(1).ownPile()), "the caller's top card is committed, so it is not in the hand any more");
        assertEquals(List.of(22), ids(e.view(2).ownPile()), "a player who has not committed yet: their top card is the one being played");
    }

    @Test
    void whileChooseIsStillOpenNothingIsLeftOut() {
        GameEngine e = humansEngine(DEFAULT, 1, hands(hand(card(11, 5), card(12, 6), card(13, 7)), hand(card(21, 5), card(22, 6))));
        assertEquals(List.of(11, 12, 13), ids(e.view(1).ownPile()), "the caller may still choose any card");
        assertEquals(List.of(21, 22), ids(e.view(2).ownPile()), "seat 2 will be offered CHOOSE once the stat is known");

        e.choose(1, 12);
        assertEquals(List.of(11, 13), ids(e.view(1).ownPile()), "the chosen card is in play; the others keep their order");
        assertEquals(List.of(21, 22), ids(e.view(2).ownPile()));

        e.call(1, "power");
        e.choose(2, 22);
    }

    @Test
    void aWinnerSeesTheCardsTheyWonAtTheBottom() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(hand(card(11, 9), card(12, 1)), hand(card(21, 2), card(22, 3))));
        e.call(1, "power");                                   // 9 beats 2: seat 1 takes 21 after their own 11
        assertEquals(2, e.round());
        // seat 1 now holds 12, 11, 21; the new round committed 12, so the rest come up as 11 then 21
        assertEquals(List.of(11, 21), ids(e.view(1).ownPile()));
    }

    @Test
    void aSeatWithNoOtherCardsHasAnEmptyPile() {
        GameEngine e = humansEngine(NO_CHOOSE, 1, hands(hand(card(11, 5)), hand(card(21, 6), card(22, 1))));
        assertEquals(List.of(), ids(e.view(1).ownPile()));
    }
}
