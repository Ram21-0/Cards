package com.cards.common;

/** A seat as shown in a lobby: who sits there (username for humans, difficulty for CPUs). */
public record SeatInfo(int seat, SeatKind kind, String username, DifficultyLevel difficulty) {
}
