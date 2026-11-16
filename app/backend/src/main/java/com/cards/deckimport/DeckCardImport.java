package com.cards.deckimport;

import java.math.BigDecimal;

/**
 * One card of a requested deck, with the six stats of the built-in Wrestling deck (same keys): {@code rank} (1 = best, lower wins),
 * {@code fight}, {@code chest} and {@code biceps} (inches, may be fractional), {@code height} (TOTAL INCHES, feet x 12 + inches) and
 * {@code weight} (lbs). {@code brand} is display-only (stored in the card's meta) and optional.
 */
public record DeckCardImport(String name, int rank, int fight, BigDecimal chest, BigDecimal biceps, int height, int weight, String brand) {
}
