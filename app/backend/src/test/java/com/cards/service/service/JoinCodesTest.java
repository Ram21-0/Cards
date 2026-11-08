package com.cards.service.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinCodesTest {

    @Test
    void codesAreSixCharsFromTheUnambiguousAlphabet() {
        for (int i = 0; i < 500; i++) {
            String code = JoinCodes.next();
            assertEquals(6, code.length());
            assertTrue(code.chars().allMatch(c -> JoinCodes.ALPHABET.indexOf(c) >= 0), code);
            assertTrue(code.matches("^[A-Z0-9]{6,8}$"), "must satisfy the DB check constraint: " + code);
        }
    }

    @Test
    void alphabetLeavesOutLookAlikes() {
        for (char c : "0O1I".toCharArray()) {
            assertTrue(JoinCodes.ALPHABET.indexOf(c) < 0, "should not contain " + c);
        }
    }

    @Test
    void codesVary() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) codes.add(JoinCodes.next());
        assertTrue(codes.size() > 190, "expected nearly all unique, got " + codes.size());
    }
}
