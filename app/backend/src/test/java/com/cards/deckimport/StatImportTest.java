package com.cards.deckimport;

import com.cards.common.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatImportTest {

    private static StatImportValidator.Raw raw(int i) {
        return new StatImportValidator.Raw("Player " + i, String.valueOf(i), "40", "38.5", "15", "72", "200", "raw");
    }

    private static List<StatImportValidator.Raw> raws(int n) {
        List<StatImportValidator.Raw> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) list.add(raw(i));
        return list;
    }

    private static String message(List<StatImportValidator.Raw> rows) {
        return assertThrows(BadRequestException.class, () -> StatImportValidator.validate(rows)).getMessage();
    }

    // ---------------------------------------------------------------- the validator

    @Test
    void aGoodImportBecomesTypedCards() {
        List<DeckCardImport> cards = StatImportValidator.validate(raws(8));
        assertEquals(8, cards.size());
        DeckCardImport first = cards.get(0);
        assertEquals(new DeckCardImport("Player 1", 1, 40, new BigDecimal("38.5"), new BigDecimal("15"), 72, 200, "raw"), first);
    }

    @Test
    void atLeastEightCardsAreNeeded() {
        assertEquals("A deck needs at least 8 cards", message(raws(7)));
        assertEquals("A deck needs at least 8 cards", message(null));
    }

    @Test
    void ranksMustBeUniqueNamesToo() {
        List<StatImportValidator.Raw> rows = raws(8);
        rows.set(4, new StatImportValidator.Raw("Player 5", "2", "40", "38", "15", "72", "200", null));
        assertTrue(message(rows).contains("Row 5 (Player 5): rank 2 is already used by Player 2 (ranks must be unique)"), message(rows));
        rows = raws(8);
        rows.set(6, new StatImportValidator.Raw("  player 1 ", "7", "40", "38", "15", "72", "200", null));
        assertTrue(message(rows).contains("duplicate name"), message(rows));
    }

    @Test
    void everyStatIsRangeAndTypeChecked() {
        String[][] bad = {
                {"rank", "0"}, {"rank", "1.5"}, {"rank", "x"}, {"rank", ""},
                {"fight", "-1"}, {"fight", "1001"},
                {"chest", "0"}, {"chest", "38.555"}, {"chest", "abc"},
                {"biceps", "61"},
                {"height", "23"}, {"height", "121"}, {"height", "72.5"},
                {"weight", "0"}, {"weight", "1501"}};
        for (String[] b : bad) {
            List<StatImportValidator.Raw> rows = raws(8);
            StatImportValidator.Raw r = rows.get(0);
            rows.set(0, new StatImportValidator.Raw(r.name(), b[0].equals("rank") ? b[1] : "1", b[0].equals("fight") ? b[1] : r.fight(), b[0].equals("chest") ? b[1] : r.chest(),
                    b[0].equals("biceps") ? b[1] : r.biceps(), b[0].equals("height") ? b[1] : r.height(), b[0].equals("weight") ? b[1] : r.weight(), r.brand()));
            String m = message(rows);
            assertTrue(m.startsWith("Row 1 (Player 1): " + b[0] + " must be"), b[0] + "=" + b[1] + " -> " + m);
        }
        assertTrue(message(raws(8).stream().map(r -> new StatImportValidator.Raw(" ", r.rank(), r.fight(), r.chest(), r.biceps(), r.height(), r.weight(), r.brand())).toList()).contains("name must be 1 to 60"));
    }

    @Test
    void heightIsMessagedAsTotalInchesAndManyErrorsAreSummarised() {
        List<StatImportValidator.Raw> rows = new ArrayList<>();
        for (int i = 1; i <= 10; i++) rows.add(new StatImportValidator.Raw("P" + i, String.valueOf(i), "1", "38", "15", "5'10", "200", null));
        String m = message(rows);
        assertTrue(m.contains("height must be a whole number from 24 to 120 (total inches)"), m);
        assertTrue(m.endsWith("; and 5 more problems"), m);
    }

    @Test
    void decimalsAreKeptTidyAndBrandIsOptional() {
        List<StatImportValidator.Raw> rows = raws(8);
        rows.set(0, new StatImportValidator.Raw("P", "1", "40", "38.50", "40", "72", "200", "  "));
        DeckCardImport c = StatImportValidator.validate(rows).get(0);
        assertEquals(new BigDecimal("38.5"), c.chest());
        assertEquals(new BigDecimal("40"), c.biceps());
        assertNull(c.brand());
        rows.set(0, new StatImportValidator.Raw("P", "1", "40", "3.8E+1", "15", "72", "200", null));
        assertEquals(0, new BigDecimal("38").compareTo(StatImportValidator.validate(rows).get(0).chest()));
        rows.set(0, new StatImportValidator.Raw("P", "1", "40", "38", "15", "72", "200", "x".repeat(31)));
        assertTrue(message(rows).contains("brand must be at most 30 characters"));
    }

    // ---------------------------------------------------------------- the CSV parser

    @Test
    void parsesCsvWithAnyColumnOrderQuotesAndBlankLines() {
        String csv = "﻿Brand, Name ,rank,fight,chest,biceps,height,weight\r\nraw,\"Smith, John\",1,44,\"38.5\",15,73,240\r\n\r\nraw,\"Say \"\"Hi\"\"\",2,40,38,15,70,200\r\n";
        List<Map<String, String>> rows = StatImportParser.parseCsv(csv);
        assertEquals(2, rows.size());
        assertEquals("Smith, John", rows.get(0).get("name"));
        assertEquals("38.5", rows.get(0).get("chest"));
        assertEquals("Say \"Hi\"", rows.get(1).get("name"));
        assertEquals("raw", rows.get(0).get("brand"));
    }

    @Test
    void aCsvWithoutAllHeaderColumnsOrWithAnUnclosedQuoteIsRefused() {
        assertTrue(assertThrows(BadRequestException.class, () -> StatImportParser.parseCsv("name,rank\nA,1")).getMessage().startsWith("The CSV needs a header row with: name, rank"));
        assertEquals("The CSV is empty", assertThrows(BadRequestException.class, () -> StatImportParser.parseCsv("  ")).getMessage());
        assertEquals("The CSV has an unclosed quote", assertThrows(BadRequestException.class, () -> StatImportParser.parseCsv("name,rank,fight,chest,biceps,height,weight\n\"A,1,1,1,1,1,1")).getMessage());
    }

    @Test
    void aCsvRowWithMissingFieldsBecomesAClearValidationError() {
        StringBuilder csv = new StringBuilder("name,rank,fight,chest,biceps,height,weight\n");
        for (int i = 1; i <= 7; i++) csv.append("P").append(i).append(',').append(i).append(",40,38,15,72,200\n");
        csv.append("P8,8,40\n");
        String m = assertThrows(BadRequestException.class, () -> StatImportValidator.fromRows(StatImportParser.parseCsv(csv.toString()))).getMessage();
        assertTrue(m.startsWith("Row 8 (P8): chest must be a number"), m);
    }

    @Test
    void aStatGeneratorsOutputGoesThroughTheSameValidation() {
        StatGenerator fake = new FakeStatGenerator();
        List<String> names = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I");
        List<DeckCardImport> generated = fake.generate("Anything", names);
        assertEquals(names.size(), StatImportValidator.fromCards(generated).size());
        assertEquals(9, generated.stream().map(DeckCardImport::rank).distinct().count(), "unique ranks");
    }

    /** What a real generator must return: one card per name, all six stats, unique ranks. */
    static final class FakeStatGenerator implements StatGenerator {
        @Override
        public List<DeckCardImport> generate(String category, List<String> names) {
            List<DeckCardImport> cards = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                cards.add(new DeckCardImport(names.get(i), i + 1, 50 - i, new BigDecimal(36 + i), new BigDecimal("14.5"), 66 + i, 180 + 5 * i, null));
            }
            return cards;
        }
    }
}
