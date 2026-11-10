package com.cards.game;

import com.cards.common.DifficultyLevel;
import com.cards.common.SeatKind;

/** Who sits in a seat: a human, or a CPU of a given difficulty. */
public record SeatSpec(SeatKind kind, DifficultyLevel difficulty) {

    public SeatSpec {
        if (kind == SeatKind.CPU && difficulty == null) throw new IllegalArgumentException("A CPU seat needs a difficulty");
    }

    public static SeatSpec human() {
        return new SeatSpec(SeatKind.HUMAN, null);
    }

    public static SeatSpec cpu(DifficultyLevel difficulty) {
        return new SeatSpec(SeatKind.CPU, difficulty);
    }

    public boolean isCpu() {
        return kind == SeatKind.CPU;
    }
}
