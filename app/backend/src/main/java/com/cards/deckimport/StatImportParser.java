package com.cards.deckimport;

import com.cards.common.BadRequestException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads a CSV (header row; columns in any order; quotes allowed) into rows of text, one map per card. */
public final class StatImportParser {

    static final int MAX_ROWS = 600;
    public static final int MAX_CSV_CHARS = 1_000_000;

    /** A parsed CSV: the lower-cased, trimmed header and one map (header -> trimmed cell) per data row. */
    public record Table(List<String> header, List<Map<String, String>> rows) {
    }

    private StatImportParser() {
    }

    /** @throws BadRequestException for an empty, oversized or unreadable CSV */
    public static Table parse(String csv) {
        if (csv == null || csv.isBlank()) throw new BadRequestException("The CSV is empty");
        if (csv.length() > MAX_CSV_CHARS) throw new BadRequestException("The CSV is larger than 1 MB");
        List<List<String>> records = records(csv.replace("\r\n", "\n").replace('\r', '\n').replaceFirst("^\\uFEFF", ""));
        if (records.isEmpty()) throw new BadRequestException("The CSV is empty");
        List<String> header = records.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        if (header.stream().distinct().count() != header.size()) throw new BadRequestException("The CSV header has the same column twice");
        if (records.size() - 1 > MAX_ROWS) throw new BadRequestException("At most " + MAX_ROWS + " rows per import");
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> fields = records.get(i);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) row.put(header.get(c), c < fields.size() ? fields.get(c).trim() : "");
            rows.add(row);
        }
        return new Table(header, rows);
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
