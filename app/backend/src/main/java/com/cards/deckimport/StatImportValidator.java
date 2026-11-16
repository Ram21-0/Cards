package com.cards.deckimport;

import com.cards.common.BadRequestException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks an import against the six stats of the built-in Wrestling deck (the new deck gets a copy of them, so keys, units and comparison
 * strategies are identical) and the rules of a playable deck: every stat present, sensible ranges, unique names, unique ranks, at least
 * {@value #MIN_CARDS} cards (a game has up to 8 seats and every seat needs a card).
 */
public final class StatImportValidator {

    public static final int MIN_CARDS = 8;
    public static final int MAX_CARDS = 500;
    public static final int MAX_NAME_LENGTH = 60;
    public static final int MAX_BRAND_LENGTH = 30;
    private static final int MAX_ERRORS_SHOWN = 5;

    private StatImportValidator() {
    }

    /** Raw text rows (from CSV) to validated cards. */
    public static List<DeckCardImport> fromRows(List<Map<String, String>> rows) {
        List<Raw> raws = new ArrayList<>();
        for (Map<String, String> row : rows) {
            raws.add(new Raw(row.get("name"), row.get("rank"), row.get("fight"), row.get("chest"), row.get("biceps"), row.get("height"), row.get("weight"), row.get("brand")));
        }
        return validate(raws);
    }

    /** Already typed cards (JSON import, or a {@link StatGenerator}) to validated cards. */
    public static List<DeckCardImport> fromCards(List<DeckCardImport> cards) {
        List<Raw> raws = new ArrayList<>();
        for (DeckCardImport c : cards) {
            raws.add(new Raw(c.name(), String.valueOf(c.rank()), String.valueOf(c.fight()), String.valueOf(c.chest()), String.valueOf(c.biceps()),
                    String.valueOf(c.height()), String.valueOf(c.weight()), c.brand()));
        }
        return validate(raws);
    }

    /** Text as it arrives: nothing is trusted. */
    public record Raw(String name, String rank, String fight, String chest, String biceps, String height, String weight, String brand) {
    }

    public static List<DeckCardImport> validate(List<Raw> raws) {
        if (raws == null || raws.size() < MIN_CARDS) throw new BadRequestException("A deck needs at least " + MIN_CARDS + " cards");
        if (raws.size() > MAX_CARDS) throw new BadRequestException("At most " + MAX_CARDS + " cards per deck");
        List<String> errors = new ArrayList<>();
        List<DeckCardImport> cards = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<Integer, String> rankOwners = new HashMap<>();
        int rowNumber = 0;
        for (Raw r : raws) {
            rowNumber++;
            String name = r.name() == null ? "" : r.name().replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
            String where = "Row " + rowNumber + (name.isEmpty() ? "" : " (" + name + ")");
            int before = errors.size();
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) errors.add(where + ": name must be 1 to " + MAX_NAME_LENGTH + " characters");
            else if (!names.add(name.toLowerCase(Locale.ROOT))) errors.add(where + ": duplicate name");
            Integer rank = integer(r.rank(), "rank", 1, 10_000, where, errors);
            Integer fight = integer(r.fight(), "fight", 0, 1_000, where, errors);
            BigDecimal chest = decimal(r.chest(), "chest", 1, 100, where, errors);
            BigDecimal biceps = decimal(r.biceps(), "biceps", 1, 60, where, errors);
            Integer height = integer(r.height(), "height", 24, 120, where, errors);
            Integer weight = integer(r.weight(), "weight", 1, 1_500, where, errors);
            String brand = r.brand() == null ? "" : r.brand().replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
            if (brand.length() > MAX_BRAND_LENGTH) errors.add(where + ": brand must be at most " + MAX_BRAND_LENGTH + " characters");
            if (rank != null) {
                String owner = rankOwners.putIfAbsent(rank, name);
                if (owner != null) errors.add(where + ": rank " + rank + " is already used by " + owner + " (ranks must be unique)");
            }
            if (errors.size() == before) cards.add(new DeckCardImport(name, rank, fight, chest, biceps, height, weight, brand.isEmpty() ? null : brand));
        }
        if (!errors.isEmpty()) {
            String shown = String.join("; ", errors.subList(0, Math.min(MAX_ERRORS_SHOWN, errors.size())));
            throw new BadRequestException(errors.size() > MAX_ERRORS_SHOWN ? shown + "; and " + (errors.size() - MAX_ERRORS_SHOWN) + " more problems" : shown);
        }
        return cards;
    }

    private static Integer integer(String text, String stat, int min, int max, String where, List<String> errors) {
        BigDecimal value = number(text);
        if (value == null || value.stripTrailingZeros().scale() > 0 || value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            errors.add(where + ": " + stat + " must be a whole number from " + min + " to " + max + (stat.equals("height") ? " (total inches)" : ""));
            return null;
        }
        return value.intValueExact();
    }

    private static BigDecimal decimal(String text, String stat, int min, int max, String where, List<String> errors) {
        BigDecimal value = number(text);
        if (value == null || value.scale() > 2 && value.stripTrailingZeros().scale() > 2 || value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            errors.add(where + ": " + stat + " must be a number from " + min + " to " + max + " with at most 2 decimals");
            return null;
        }
        return value.stripTrailingZeros().scale() < 0 ? value.setScale(0) : value.stripTrailingZeros();
    }

    private static BigDecimal number(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
