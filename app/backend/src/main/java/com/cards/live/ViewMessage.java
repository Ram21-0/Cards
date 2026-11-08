package com.cards.live;

import com.cards.service.controller.dto.CardResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What ONE connection is told about the game right now (the personalised PlayerView, RULES.md §8). It holds only that
 * seat's own cards; every other seat appears as a card count.
 */
public record ViewMessage(String type, Instant serverTime, int seat, String phase, int round, int callerSeat, String stat,
                          int clashLevel, int potSize, Instant deadline, List<Integer> contenders,
                          Map<Integer, Integer> handSizes, List<CardResponse> ownHand, CardResponse committed,
                          boolean canChoose, boolean canCall, Map<Integer, Integer> placements) {
}
