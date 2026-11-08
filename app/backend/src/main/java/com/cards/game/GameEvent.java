package com.cards.game;

import com.cards.common.Card;

import java.util.List;
import java.util.Map;

/**
 * What happened, returned by every command (RULES.md §9). Events are safe to broadcast: a card appears in an event only
 * when it is revealed ({@link Revealed}); {@link CardChosen} says who chose, never what.
 */
public sealed interface GameEvent {

    /** A new round: its number, who calls, and which seats still hold cards. */
    record RoundStarted(int round, int caller, List<Integer> seats) implements GameEvent {
    }

    /** A CHOOSE-eligible player committed a card ({@code auto}: the time ran out and their top card was used). */
    record CardChosen(int seat, boolean auto) implements GameEvent {
    }

    /**
     * A stat was set. {@code seat} is the caller; null when nobody called in a clash and the server picked a random
     * stat. {@code auto}: the time ran out and the server picked the stat at random.
     */
    record StatCalled(Integer seat, String stat, boolean auto) implements GameEvent {
    }

    /** The cards played, by seat. */
    record Revealed(Map<Integer, Card> cards) implements GameEvent {
    }

    /** A player took the round; {@code cardsWon} is every card now on the bottom of their pile (their own, the others', the pot). */
    record RoundWon(int seat, int cardsWon) implements GameEvent {
    }

    /** A tie for best: the players still in it, the clash level (1, 2 … on repeats) and the pot so far. */
    record ClashStarted(List<Integer> tiedSeats, int level, int potSize) implements GameEvent {
    }

    /** Nobody in the clash had a next card: the pot waits for a later round's winner. */
    record PotCarried(int potSize) implements GameEvent {
    }

    record PlayerEliminated(int seat, int placement) implements GameEvent {
    }

    /** The end: placement by seat (1 = winner; ties share a number) and whether the round cap ended it. */
    record GameOver(Map<Integer, Integer> placements, boolean endedByRoundCap) implements GameEvent {
    }
}
