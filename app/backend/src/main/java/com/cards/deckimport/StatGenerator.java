package com.cards.deckimport;

import java.util.List;

/**
 * Stage 2b (OPTIONAL, not wired to anything): produces the stats for a requested deck automatically. Needs a paid API key, so no real
 * implementation exists until the owner confirms. Its output is a CSV-shaped table, so it goes through the same {@link ImportValidator} as a
 * manual import.
 */
public interface StatGenerator {

    /** One row per suggested name with name, rank, fight, chest, biceps, height, weight (and optionally brand, image); ranks unique. */
    StatImportParser.Table generate(String category, List<String> names);
}
