package com.cards.service.service;

/** What we trust from a verified Google ID token. {@code sub} is the stable account id; never use the email as identity. */
public record GoogleIdentity(String sub, String email, boolean emailVerified, String name, String picture, String nonce) {
}
