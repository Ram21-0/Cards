package com.cards.game;

import com.cards.common.Card;
import com.cards.common.Outcome;
import com.cards.common.StatDefinition;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * For every card and stat: the share of the rest of the deck that card beats (0..1), judged with the stat's own
 * comparison strategy (so "lower wins" stats work). The CPU uses it to tell a strong stat from a weak one.
 */
final class CardStrengths {

    private final Map<Integer, double[]> byCard = new HashMap<>();

    CardStrengths(Collection<Card> deck, List<StatDefinition> stats) {
        List<Card> cards = List.copyOf(deck);
        int others = Math.max(1, cards.size() - 1);
        for (Card card : cards) {
            double[] shares = new double[stats.size()];
            for (int s = 0; s < stats.size(); s++) {
                StatDefinition stat = stats.get(s);
                int beaten = 0;
                for (Card other : cards) {
                    if (other != card && stat.compare(card.stat(stat.key()), other.stat(stat.key())) == Outcome.WIN) beaten++;
                }
                shares[s] = beaten / (double) others;
            }
            byCard.put(card.id(), shares);
        }
    }

    /** Share of the deck {@code card} beats on the stat at {@code statIndex}. */
    double of(Card card, int statIndex) {
        return byCard.get(card.id())[statIndex];
    }

    /** How strong the card's best stat is. */
    double best(Card card) {
        double best = 0;
        for (double v : byCard.get(card.id())) best = Math.max(best, v);
        return best;
    }
}
