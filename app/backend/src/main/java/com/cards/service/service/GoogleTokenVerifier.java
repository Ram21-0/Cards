package com.cards.service.service;

/**
 * Verifies a Google ID token. The real implementation talks to Google's public keys; tests use a fake,
 * so no test ever calls Google.
 */
public interface GoogleTokenVerifier {

    /**
     * @throws com.cards.common.UnauthorizedException       forged, malformed, expired, wrong audience or issuer
     * @throws com.cards.common.ServiceUnavailableException not configured (no client id) or Google unreachable
     */
    GoogleIdentity verify(String credential);
}
