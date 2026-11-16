package com.cards.deckimport;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A validated card ready to be saved. {@code imageUrl} is an https URL to store as given, or null; {@code image} is an uploaded file that has
 * been decoded and re-encoded and still has to be stored (its URL is set once it is).
 */
public record ImportedCard(String name, String imageUrl, ProcessedImage image, Map<String, BigDecimal> stats, String brand) {

    public ImportedCard withImageUrl(String url) {
        return new ImportedCard(name, url, null, stats, brand);
    }
}
