package com.cards.deckimport;

import com.cards.common.BadRequestException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads a CSV import (header row; columns in any order; quotes allowed) into raw rows of text, one map per card. */
public final class StatImportParser {

    public static final List<String> COLUMNS = List.of("name", "rank", "fight", "chest", "biceps", "height", "weight", "brand");
    private static final List<String> REQUIRED = List.of("name", "rank", "fight", "chest", "biceps", "height", "weight");
    static final int MAX_ROWS = 600;

    private StatImportParser() {
    }

    /** @throws BadRequestException for a missing header column or an unreadable file */
    public static List<Map<String, String>> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) throw new BadRequestException("The CSV is empty");
        List<List<String>> records = records(csv.replace("\r\n", "\n").replace('\r', '\n').replaceFirst("^\\uFEFF", ""));
        if (records.isEmpty()) throw new BadRequestException("The CSV is empty");
        List<String> header = records.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        for (String column : REQUIRED) {
            if (!header.contains(column)) throw new BadRequestException("The CSV needs a header row with: " + String.join(", ", COLUMNS) + " (brand is optional)");
        }
        if (records.size() - 1 > MAX_ROWS) throw new BadRequestException("At most " + MAX_ROWS + " cards per import");
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> fields = records.get(i);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) row.put(header.get(c), c < fields.size() ? fields.get(c).trim() : "");
            rows.add(row);
        }
        return rows;
    }

    /** RFC 4180-ish: commas, double quotes with "" escaping, newlines inside quotes; blank lines are skipped. */
    private static List<List<String>> records(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                fields.add(field.toString());
                field.setLength(0);
                addRecord(records, fields);
                fields = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (quoted) throw new BadRequestException("The CSV has an unclosed quote");
        fields.add(field.toString());
        addRecord(records, fields);
        return records;
    }

    private static void addRecord(List<List<String>> records, List<String> fields) {
        boolean blank = fields.stream().allMatch(f -> f.isBlank());
        if (!blank) records.add(fields);
    }
}
