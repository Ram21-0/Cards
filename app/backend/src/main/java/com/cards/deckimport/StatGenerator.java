package com.cards.deckimport;

import java.util.List;

/**
 * Stage 2b (OPTIONAL, not wired to anything): produces the stats for a requested deck automatically. Needs a paid API key, so no real
 * implementation exists until the owner confirms. Whatever it returns goes through the same {@link StatImportValidator} as a manual import.
 */
public interface StatGenerator {

    /** One card per suggested name, all six stats filled in, ranks unique. */
    List<DeckCardImport> generate(String category, List<String> names);
}
