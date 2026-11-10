package com.cards.service.service;

import com.cards.common.ServiceUnavailableException;
import com.cards.common.UnauthorizedException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * {@link GoogleTokenVerifier} on top of google-api-client. Its {@link GoogleIdTokenVerifier} checks the RSA signature
 * against Google's published keys, the issuer, the expiry and that the audience is OUR client id.
 */
public class GoogleApiTokenVerifier implements GoogleTokenVerifier {

    private final JsonFactory json;
    private final GoogleIdTokenVerifier verifier;     // null when no client id is configured

    /** Production constructor: with an empty client id every verification answers "not configured". */
    public GoogleApiTokenVerifier(String clientId, HttpTransport transport, JsonFactory json) {
        this(json, clientId == null || clientId.isBlank() ? null
                : new GoogleIdTokenVerifier.Builder(transport, json).setAudience(List.of(clientId.trim())).build());
    }

    /** For tests: supply a verifier built with offline keys. */
    GoogleApiTokenVerifier(JsonFactory json, GoogleIdTokenVerifier verifier) {
        this.json = json;
        this.verifier = verifier;
    }

    @Override
    public GoogleIdentity verify(String credential) {
        if (verifier == null) throw new ServiceUnavailableException("Google sign-in is not configured");
        if (credential == null || credential.isBlank()) throw new UnauthorizedException("Invalid Google token");

        GoogleIdToken token;
        try {
            token = GoogleIdToken.parse(json, credential);
        } catch (IOException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid Google token");              // not even a well-formed JWT
        }

        boolean valid;
        try {
            valid = verifier.verify(token);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            // Google's public keys could not be fetched or understood: our problem, not the caller's
            throw new ServiceUnavailableException("Could not verify the Google token right now");
        }
        if (!valid) throw new UnauthorizedException("Invalid Google token");       // signature / audience / issuer / expiry

        GoogleIdToken.Payload p = token.getPayload();
        return new GoogleIdentity(
                p.getSubject(),
                p.getEmail(),
                Boolean.TRUE.equals(p.getEmailVerified()),
                (String) p.get("name"),
                (String) p.get("picture"),
                p.getNonce());
    }
}
