package com.cards.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelTest {

    private static final UUID DECK_ID = UUID.randomUUID();

    private static StatDefinition def(String key, ComparisonStrategy strategy, int order) {
        return StatDefinition.builder().deckId(DECK_ID).key(key).label(key).valueType(ValueType.INT)
                .comparisonStrategy(strategy).displayOrder(order).build();
    }

    private static Card card(String name, int rank, int height) {
        return Card.builder().deckId(DECK_ID).name(name).metadata(Map.of())
                .stats(List.of(new Stat("rank", BigDecimal.valueOf(rank)), new Stat("height", BigDecimal.valueOf(height))))
                .build();
    }

    private static final Deck DECK = Deck.builder().id(DECK_ID).name("Wrestling").visibility(Visibility.PUBLIC)
            .stats(List.of(def("rank", ComparisonStrategy.LOWER_WINS, 1), def("height", ComparisonStrategy.HIGHER_WINS, 2)))
            .build();

    @Test
    void lowerRankWins() {
        assertEquals(Outcome.WIN, DECK.compare(card("A", 1, 70), card("B", 5, 70), "rank"));
        assertEquals(Outcome.LOSS, DECK.compare(card("A", 9, 70), card("B", 5, 70), "rank"));
    }

    @Test
    void higherHeightWins() {
        assertEquals(Outcome.WIN, DECK.compare(card("A", 1, 73), card("B", 1, 71), "height"));
    }

    @Test
    void equalValuesTie() {
        assertEquals(Outcome.TIE, DECK.compare(card("A", 3, 70), card("B", 4, 70), "height"));
    }

    @Test
    void decimalsCompareByValueNotScale() {
        // 38.0 vs 38 must tie (BigDecimal.equals would say different)
        assertEquals(Outcome.TIE, ComparisonStrategy.HIGHER_WINS.compare(new BigDecimal("38.0"), new BigDecimal("38")));
    }

    @Test
    void unknownStatIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DECK.compare(card("A", 1, 70), card("B", 2, 70), "runs"));
    }

    @Test
    void displayFormats() {
        assertEquals("6'1\"", DisplayFormat.FEET_INCHES.format(new BigDecimal("73"), "in"));
        assertEquals("6'0\"", DisplayFormat.FEET_INCHES.format(new BigDecimal("72"), "in"));
        assertEquals("5'11\"", DisplayFormat.FEET_INCHES.format(new BigDecimal("71"), "in"));
        assertEquals("6'1.5\"", DisplayFormat.FEET_INCHES.format(new BigDecimal("73.5"), "in"));
        assertEquals("38.5", DisplayFormat.PLAIN.format(new BigDecimal("38.50"), "in"));
        assertEquals("15", DisplayFormat.PLAIN.format(new BigDecimal("15.0"), "in"));
        assertEquals("0", DisplayFormat.PLAIN.format(new BigDecimal("0.00"), null));
        assertEquals("240lbs", DisplayFormat.WITH_UNIT.format(new BigDecimal("240"), "lbs"));
        assertEquals("240", DisplayFormat.WITH_UNIT.format(new BigDecimal("240"), null));
    }

    @Test
    void deckDisplaysCardStatThroughItsDefinition() {
        StatDefinition height = StatDefinition.builder().deckId(DECK_ID).key("height").label("Height").unit("in")
                .valueType(ValueType.INT).comparisonStrategy(ComparisonStrategy.HIGHER_WINS)
                .displayFormat(DisplayFormat.FEET_INCHES).displayOrder(1).build();
        Deck deck = Deck.builder().id(DECK_ID).name("W").visibility(Visibility.PUBLIC).stats(List.of(height)).build();
        assertEquals("6'1\"", deck.display(card("A", 1, 73), "height"));
    }

    @Test
    void dbCodesRoundTrip() {
        for (Visibility v : Visibility.values()) assertEquals(v, Visibility.get(v.dbVal()));
        for (ComparisonStrategy c : ComparisonStrategy.values()) assertEquals(c, ComparisonStrategy.get(c.dbVal()));
        for (GameStatus s : GameStatus.values()) assertEquals(s, GameStatus.get(s.dbVal()));
        for (DisplayFormat f : DisplayFormat.values()) assertEquals(f, DisplayFormat.get(f.dbVal()));
        for (SeatKind k : SeatKind.values()) assertEquals(k, SeatKind.get(k.dbVal()));
        assertThrows(IllegalArgumentException.class, () -> Visibility.get(99));
    }
}
