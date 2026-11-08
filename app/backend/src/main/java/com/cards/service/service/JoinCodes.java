package com.cards.service.service;

import java.security.SecureRandom;

/** Short shareable game codes, e.g. K7Q2XM. Alphabet leaves out look-alikes (0/O, 1/I). */
final class JoinCodes {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    private JoinCodes() {
    }

    static String next() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
