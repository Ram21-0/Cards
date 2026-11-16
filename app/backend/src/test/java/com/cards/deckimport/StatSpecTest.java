package com.cards.deckimport;

import com.cards.common.ComparisonStrategy;
import com.cards.common.DisplayFormat;
import com.cards.common.StatDefinition;
import com.cards.common.ValueType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The admin's import uses the template deck's stat definitions with tight ranges (the six Wrestling stats). */
class StatSpecTest {

    private static StatDefinition def(String key, ValueType type, ComparisonStrategy strategy, DisplayFormat format) {
        return StatDefinition.builder().key(key).label(key).valueType(type).comparisonStrategy(strategy).displayFormat(format).displayOrder(1).build();
    }

    private static List<StatSpec> wrestling() {
        return List.of(StatSpec.fromDefinition(def("rank", ValueType.INT, ComparisonStrategy.LOWER_WINS, DisplayFormat.PLAIN)),
                StatSpec.fromDefinition(def("fight", ValueType.INT, ComparisonStrategy.HIGHER_WINS, DisplayFormat.PLAIN)),
                StatSpec.fromDefinition(def("chest", ValueType.DECIMAL, ComparisonStrategy.HIGHER_WINS, DisplayFormat.PLAIN)),
                StatSpec.fromDefinition(def("biceps", ValueType.DECIMAL, ComparisonStrategy.HIGHER_WINS, DisplayFormat.PLAIN)),
                StatSpec.fromDefinition(def("height", ValueType.INT, ComparisonStrategy.HIGHER_WINS, DisplayFormat.FEET_INCHES)),
                StatSpec.fromDefinition(def("weight", ValueType.INT, ComparisonStrategy.HIGHER_WINS, DisplayFormat.WITH_UNIT)));
    }

    private static String row(int i, String height) {
        return "P" + i + "," + i + ",40," + (30 + i) + ".5,15," + height + ",200,raw\n";
    }

    private static ImportValidator.Result run(String csv) {
        return ImportValidator.validate(StatImportParser.parse(csv), wrestling(), Map.of(), new ImageProcessor(), ImportValidator.Options.ADMIN);
    }

    @Test
    void theRangesOfTheWrestlingStatsAreEnforced() {
        StringBuilder good = new StringBuilder("name,rank,fight,chest,biceps,height,weight,brand\n");
        for (int i = 1; i <= 8; i++) good.append(row(i, "72"));
        assertTrue(run(good.toString()).ok());
        for (String bad : new String[]{"23", "121", "72.5", "x"}) {
            StringBuilder csv = new StringBuilder("name,rank,fight,chest,biceps,height,weight,brand\n").append(row(1, bad));
            for (int i = 2; i <= 8; i++) csv.append(row(i, "72"));
            ImportValidator.Result r = run(csv.toString());
            assertFalse(r.ok(), bad);
            assertTrue(r.errors().get(0).startsWith("Row 1 (P1): height must be a number from 24 to 120 (a whole number), as total inches"), r.errors().get(0));
        }
    }

    @Test
    void theSpecsKeepTheTemplatesValueTypeAndDirection() {
        List<StatSpec> specs = wrestling();
        assertEquals(ValueType.INT, specs.get(0).valueType());
        assertFalse(specs.get(0).higherWins(), "rank: lower wins");
        assertTrue(specs.get(1).higherWins());
        assertEquals(ValueType.DECIMAL, specs.get(2).valueType());
        assertEquals(new BigDecimal("100"), specs.get(2).max());
        StringBuilder csv = new StringBuilder("name,rank,fight,chest,biceps,height,weight,brand\n");
        for (int i = 1; i <= 8; i++) csv.append(row(i, "72"));
        assertEquals(ValueType.DECIMAL, run(csv.toString()).specs().get(3).valueType(), "biceps stays DECIMAL although every cell is whole: it comes from the template");
        List<String> keys = new ArrayList<>();
        specs.forEach(s -> keys.add(s.key()));
        assertEquals(List.of("rank", "fight", "chest", "biceps", "height", "weight"), keys);
    }
}
