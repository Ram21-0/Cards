package com.cards.deckimport;

import com.cards.common.BadRequestException;
import com.cards.common.DisplayFormat;
import com.cards.common.ValueType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The stat mapping of an upload, as JSON:
 * <pre>{"stats": [{"column": "rank", "label": "Rank", "unit": null, "higherWins": false, "displayFormat": "PLAIN"}, ...]}</pre>
 * {@code key} is optional (default: the column name), {@code displayFormat} is PLAIN, WITH_UNIT or FEET_INCHES (default PLAIN).
 */
public final class MappingParser {

    public static final int MAX_STATS = 8;
    public static final int MAX_MAPPING_CHARS = 20_000;
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,19}");
    private static final Set<String> RESERVED = Set.of("name", "image", "brand");

    private MappingParser() {
    }

    public static List<StatSpec> parse(ObjectMapper json, String mapping) {
        if (mapping == null || mapping.isBlank()) throw new BadRequestException("mapping is required");
        if (mapping.length() > MAX_MAPPING_CHARS) throw new BadRequestException("mapping is too large");
        JsonNode root;
        try {
            root = json.readTree(mapping);
        } catch (Exception e) {
            throw new BadRequestException("mapping is not valid JSON");
        }
        JsonNode stats = root == null ? null : root.get("stats");
        if (stats == null || !stats.isArray() || stats.isEmpty()) throw new BadRequestException("mapping needs a non-empty \"stats\" list");
        if (stats.size() > MAX_STATS) throw new BadRequestException("A deck can have at most " + MAX_STATS + " stats");
        List<StatSpec> specs = new ArrayList<>();
        Set<String> keys = new HashSet<>(), columns = new HashSet<>();
        int n = 0;
        for (JsonNode s : stats) {
            n++;
            String column = text(s, "column");
            if (column == null) throw new BadRequestException("stat " + n + ": \"column\" is required");
            column = column.trim().toLowerCase(Locale.ROOT);
            if (RESERVED.contains(column)) throw new BadRequestException("stat " + n + ": '" + column + "' is not a stat column (name, image and brand are reserved)");
            String key = text(s, "key") == null ? column : text(s, "key").trim().toLowerCase(Locale.ROOT);
            if (!KEY.matcher(key).matches()) throw new BadRequestException("stat " + n + ": key must be lower-case letters, digits or _ and start with a letter (up to 20 characters)");
            if (!keys.add(key)) throw new BadRequestException("stat " + n + ": key '" + key + "' is used twice");
            if (!columns.add(column)) throw new BadRequestException("stat " + n + ": column '" + column + "' is used twice");
            String label = Text.clean(text(s, "label") == null ? column : text(s, "label"));
            if (label.isEmpty() || label.length() > 20) throw new BadRequestException("stat " + n + ": label must be 1 to 20 characters");
            String unit = Text.clean(text(s, "unit"));
            if (unit.length() > 8) throw new BadRequestException("stat " + n + ": unit must be at most 8 characters");
            JsonNode higher = s.get("higherWins");
            if (higher == null || !higher.isBoolean()) throw new BadRequestException("stat " + n + ": \"higherWins\" must be true or false");
            DisplayFormat format = DisplayFormat.PLAIN;
            if (text(s, "displayFormat") != null) {
                try {
                    format = DisplayFormat.valueOf(text(s, "displayFormat").trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new BadRequestException("stat " + n + ": displayFormat must be PLAIN, WITH_UNIT or FEET_INCHES");
                }
            }
            if (format == DisplayFormat.WITH_UNIT && unit.isEmpty()) throw new BadRequestException("stat " + n + ": displayFormat WITH_UNIT needs a unit");
            specs.add(new StatSpec(column, key, label, unit.isEmpty() ? null : unit, higher.asBoolean(), format == DisplayFormat.FEET_INCHES ? ValueType.INT : null,
                    format, StatSpec.USER_MIN, StatSpec.USER_MAX, 2));
        }
        return specs;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
