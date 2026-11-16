package com.cards.deckimport;

import com.cards.common.BadRequestException;
import com.cards.common.DisplayFormat;
import com.cards.common.ValueType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV parser, stat mapping and the validator, including hostile input. */
class ImportPipelineTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ImageProcessor pictures = new ImageProcessor();

    private static final String MAPPING = """
            {"stats": [
              {"column": "rank", "label": "Rank", "higherWins": false},
              {"column": "power", "label": "Power", "unit": "pts", "higherWins": true, "displayFormat": "WITH_UNIT"},
              {"column": "height", "label": "Height", "unit": "in", "higherWins": true, "displayFormat": "FEET_INCHES"}]}""";

    private List<StatSpec> specs() {
        return MappingParser.parse(json, MAPPING);
    }

    private static String csv(int rows) {
        StringBuilder sb = new StringBuilder("name,image,brand,rank,power,height\n");
        for (int i = 1; i <= rows; i++) sb.append("Card ").append(i).append(",,raw,").append(i).append(',').append(10 + i).append(".5,").append(60 + i).append('\n');
        return sb.toString();
    }

    private ImportValidator.Result validate(String csv, Map<String, byte[]> uploads) {
        return ImportValidator.validate(StatImportParser.parse(csv), specs(), uploads, pictures, ImportValidator.Options.USER);
    }

    private ImportValidator.Result validate(String csv) {
        return validate(csv, Map.of());
    }

    // ---------------------------------------------------------------- the mapping

    @Test
    void aMappingBecomesStatSpecsInColumnOrder() {
        List<StatSpec> specs = specs();
        assertEquals(List.of("rank", "power", "height"), specs.stream().map(StatSpec::key).toList());
        assertFalse(specs.get(0).higherWins());
        assertEquals("pts", specs.get(1).unit());
        assertEquals(DisplayFormat.WITH_UNIT, specs.get(1).displayFormat());
        assertEquals(DisplayFormat.FEET_INCHES, specs.get(2).displayFormat());
        assertEquals(ValueType.INT, specs.get(2).valueType(), "feet/inches is whole inches");
        assertNull(specs.get(0).valueType(), "found from the data");
    }

    @Test
    void badMappingsAreRefusedWithAClearMessage() {
        String[][] bad = {
                {"", "mapping is required"}, {"not json", "mapping is not valid JSON"}, {"{}", "mapping needs a non-empty \"stats\" list"}, {"{\"stats\": []}", "mapping needs a non-empty \"stats\" list"},
                {"{\"stats\": [{\"label\": \"x\", \"higherWins\": true}]}", "stat 1: \"column\" is required"},
                {"{\"stats\": [{\"column\": \"name\", \"higherWins\": true}]}", "stat 1: 'name' is not a stat column (name, image and brand are reserved)"},
                {"{\"stats\": [{\"column\": \"image\", \"higherWins\": true}]}", "stat 1: 'image' is not a stat column (name, image and brand are reserved)"},
                {"{\"stats\": [{\"column\": \"a\"}]}", "stat 1: \"higherWins\" must be true or false"},
                {"{\"stats\": [{\"column\": \"a\", \"higherWins\": \"yes\"}]}", "stat 1: \"higherWins\" must be true or false"},
                {"{\"stats\": [{\"column\": \"a\", \"higherWins\": true, \"displayFormat\": \"FANCY\"}]}", "stat 1: displayFormat must be PLAIN, WITH_UNIT or FEET_INCHES"},
                {"{\"stats\": [{\"column\": \"a\", \"higherWins\": true, \"displayFormat\": \"WITH_UNIT\"}]}", "stat 1: displayFormat WITH_UNIT needs a unit"},
                {"{\"stats\": [{\"column\": \"a\", \"higherWins\": true}, {\"column\": \"a\", \"higherWins\": true}]}", "stat 2: key 'a' is used twice"},
                {"{\"stats\": [{\"column\": \"a b\", \"higherWins\": true}]}", "stat 1: key must be lower-case letters, digits or _ and start with a letter (up to 20 characters)"},
                {"{\"stats\": [{\"column\": \"a\", \"higherWins\": true, \"label\": \"" + "x".repeat(21) + "\"}]}", "stat 1: label must be 1 to 20 characters"},
                {"{\"stats\": [{\"column\": \"a\", \"higherWins\": true, \"unit\": \"123456789\"}]}", "stat 1: unit must be at most 8 characters"}};
        for (String[] b : bad) {
            assertEquals(b[1], assertThrows(BadRequestException.class, () -> MappingParser.parse(json, b[0])).getMessage(), b[0]);
        }
    }

    @Test
    void atMostEightStats() {
        StringBuilder sb = new StringBuilder("{\"stats\": [");
        for (int i = 0; i < 9; i++) sb.append(i > 0 ? "," : "").append("{\"column\": \"c").append(i).append("\", \"higherWins\": true}");
        assertEquals("A deck can have at most 8 stats", assertThrows(BadRequestException.class, () -> MappingParser.parse(json, sb + "]}")).getMessage());
        assertEquals(8, MappingParser.parse(json, sb.substring(0, sb.lastIndexOf(",{")) + "]}").size());
    }

    // ---------------------------------------------------------------- the CSV

    @Test
    void parsesQuotesBomCrlfAndBlankLines() {
        StatImportParser.Table t = StatImportParser.parse("﻿Name, Rank \r\n\"Smith, \"\"Big\"\" John\",1\r\n\r\n  Bo  ,2\r\n");
        assertEquals(List.of("name", "rank"), t.header());
        assertEquals("Smith, \"Big\" John", t.rows().get(0).get("name"));
        assertEquals("Bo", t.rows().get(1).get("name"));
    }

    @Test
    void hostileCsvFilesAreRefusedOrHarmless() {
        assertEquals("The CSV is empty", assertThrows(BadRequestException.class, () -> StatImportParser.parse(" \n ")).getMessage());
        assertEquals("The CSV has an unclosed quote", assertThrows(BadRequestException.class, () -> StatImportParser.parse("name,rank\n\"A,1\n")).getMessage());
        assertEquals("The CSV header has the same column twice", assertThrows(BadRequestException.class, () -> StatImportParser.parse("name,rank,RANK\nA,1,2")).getMessage());
        assertEquals("The CSV is larger than 1 MB", assertThrows(BadRequestException.class, () -> StatImportParser.parse("name\n" + "x".repeat(StatImportParser.MAX_CSV_CHARS))).getMessage());
        StringBuilder many = new StringBuilder("name\n");
        for (int i = 0; i < StatImportParser.MAX_ROWS + 1; i++) many.append("n").append(i).append('\n');
        assertEquals("At most 600 rows per import", assertThrows(BadRequestException.class, () -> StatImportParser.parse(many.toString())).getMessage());
        // binary garbage and NUL bytes parse as text and then fail validation like any other bad cell, never crash
        StatImportParser.Table binary = StatImportParser.parse("name,rank,power,height\n\u0000\u0001\u0002,￿,\u0003,x\n");
        assertFalse(ImportValidator.validate(binary, specs(), Map.of(), pictures, new ImportValidator.Options(1, 10)).ok());
    }

    @Test
    void formulaAndHtmlInCellsAreStoredAsPlainTextNeverInterpreted() {
        String c = "name,rank,power,height\n\"=HYPERLINK(\"\"http://evil\"\")\",1,5,70\n<script>alert(1)</script>,2,6,71\n+1+1,3,7,72\n@SUM(A1),4,8,73\n-2+3,5,9,74\nF,6,9,74\nG,7,9,74\nH,8,9,74\n";
        ImportValidator.Result r = validate(c);
        assertTrue(r.ok(), r.errors().toString());
        assertEquals("=HYPERLINK(\"http://evil\")", r.cards().get(0).name());
        assertEquals("<script>alert(1)</script>", r.cards().get(1).name());
    }

    // ---------------------------------------------------------------- the validator

    @Test
    void aGoodUploadBecomesCardsAndTheValueTypesAreFoundFromTheData() {
        ImportValidator.Result r = validate(csv(8));
        assertTrue(r.ok(), r.errors().toString());
        assertEquals(8, r.cards().size());
        assertEquals(new BigDecimal("11.5"), r.cards().get(0).stats().get("power"));
        assertEquals(new BigDecimal("1"), r.cards().get(0).stats().get("rank"));
        assertEquals("raw", r.cards().get(0).brand());
        assertEquals(ValueType.INT, r.specs().get(0).valueType());
        assertEquals(ValueType.DECIMAL, r.specs().get(1).valueType(), "11.5 is not whole");
    }

    @Test
    void theNumberOfCardsIsBounded() {
        assertEquals(List.of("A deck needs at least 8 cards (the CSV has 7)"), validate(csv(7)).errors());
        assertEquals(List.of("A deck can have at most 100 cards (the CSV has 101)"), validate(csv(101)).errors());
        assertTrue(validate(csv(100)).ok());
    }

    @Test
    void namesAndRanksMustBeUniqueAndEveryProblemIsReportedAtOnce() {
        String c = csv(8).replace("Card 3,,raw,3,", "card 2,,raw,2,").replace("Card 5,,raw,5,13.5,65", "Card 5,,raw,2,13.5,65");
        ImportValidator.Result r = validate(c);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("Row 3 (card 2): duplicate name")), r.errors().toString());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("rank 2 is already used by")), r.errors().toString());
        assertTrue(r.errors().size() >= 2, "both problems in one answer");
        assertTrue(r.cards().isEmpty());
    }

    @Test
    void numbersMustParseAndStayInRange() {
        String[] badCells = {"abc", "", "1e5", "1,5", "NaN", "Infinity", "0x10", "1000001", "-1000001", "1.234", "--1", "١٢٣"};
        for (String cell : badCells) {
            String c = csv(8).replace("Card 1,,raw,1,11.5,61", "Card 1,,raw,1,\"" + cell + "\",61");
            ImportValidator.Result r = validate(c);
            assertFalse(r.ok(), "power = '" + cell + "' must be refused");
            assertTrue(r.errors().get(0).startsWith("Row 1 (Card 1): power must be a number from -1000000 to 1000000"), r.errors().get(0));
        }
        assertFalse(validate(csv(8).replace("Card 2,,raw,2,12.5,62", "Card 2,,raw,2,12.5,62.5")).ok(), "height is whole inches");
        assertTrue(validate(csv(8).replace("Card 2,,raw,2,12.5,62", "Card 2,,raw,2,12.50,62")).ok());
    }

    @Test
    void aMissingColumnOrNameIsOneClearError() {
        assertEquals(List.of("The CSV needs a 'name' column"), validate("rank,power,height\n1,1,1\n").errors());
        assertEquals(List.of("The CSV has no column 'power'"), validate("name,rank,height\n" + "A,1,70\n".repeat(8)).errors());
        assertEquals("Row 1: name must be 1 to 60 characters", validate("name,rank,power,height\n,1,1,70\n" + "B,2,1,70\n".repeat(7)).errors().get(0));
        assertTrue(validate("name,rank,power,height\n" + "x".repeat(61) + ",1,1,70\n" + "B,2,1,70\n".repeat(7)).errors().get(0).contains("name must be 1 to 60 characters"));
    }

    @Test
    void errorsAreCappedAtFiftyButCounted() {
        StringBuilder c = new StringBuilder("name,rank,power,height\n");
        for (int i = 1; i <= 100; i++) c.append("C").append(i).append(",").append(i).append(",bad,70\n");
        ImportValidator.Result r = validate(c.toString());
        assertEquals(50, r.errors().size());
        assertEquals(100, r.errorCount());
    }

    // ---------------------------------------------------------------- images in the CSV

    @Test
    void anHttpsUrlIsStoredAsGivenAndNeverFetched() {
        String c = csv(8).replace("Card 1,,", "Card 1,https://cdn.example.com/a.jpg?x=1&y=2,");
        ImportValidator.Result r = validate(c);
        assertTrue(r.ok(), r.errors().toString());
        assertEquals("https://cdn.example.com/a.jpg?x=1&y=2", r.cards().get(0).imageUrl());
        assertNull(r.cards().get(0).image());
    }

    @Test
    void dangerousOrOddImageAddressesAreRefused() {
        String[] bad = {"http://example.com/a.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA", "ftp://example.com/a.png", "file:///etc/passwd",
                "https://user:pw@example.com/a.png", "https:///nohost", "https://exa mple.com/a.png", "https://example.com/\"onerror=alert(1)",
                "https://example.com/" + "a".repeat(500), "gopher://x/1", "//example.com/a.png:80"};
        for (String url : bad) {
            ImportValidator.Result r = validate(csv(8).replace("Card 1,,", "Card 1,\"" + url.replace("\"", "\"\"") + "\","));
            assertFalse(r.ok(), url);
        }
    }

    @Test
    void anUploadedFileNameIsLookedUpAndDecodedOnceEvenWhenManyCardsShareIt() {
        byte[] image = TestImages.jpeg(100, 100);
        String c = csv(8).replace("Card 1,,", "Card 1,Photos/A.JPG,").replace("Card 2,,", "Card 2,a.jpg,");
        ImportValidator.Result r = validate(c, Map.of("a.jpg", image));
        assertTrue(r.ok(), r.errors().toString());
        assertNotNull(r.cards().get(0).image());
        assertSame(r.cards().get(0).image(), r.cards().get(1).image(), "the same file is processed once");
    }

    @Test
    void aMissingOrBadUploadedImageIsARowError() {
        ImportValidator.Result missing = validate(csv(8).replace("Card 1,,", "Card 1,nothere.png,"));
        assertEquals("Row 1 (Card 1): image 'nothere.png' was not uploaded", missing.errors().get(0));
        ImportValidator.Result bad = validate(csv(8).replace("Card 1,,", "Card 1,evil.png,"), Map.of("evil.png", "<script>alert(1)</script>".getBytes()));
        assertEquals("Row 1 (Card 1): image 'evil.png' only PNG, JPEG and GIF images are accepted", bad.errors().get(0));
    }
}
