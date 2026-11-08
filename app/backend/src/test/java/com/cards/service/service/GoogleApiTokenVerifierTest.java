package com.cards.service.service;

import com.cards.common.ServiceUnavailableException;
import com.cards.common.UnauthorizedException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.Json;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the REAL google-api-client verification (signature, issuer, audience, expiry) but fully offline: the
 * "Google public keys" are served by a mock HTTP transport, and tokens are signed with keys generated here.
 * Nothing in this test talks to Google.
 */
class GoogleApiTokenVerifierTest {

    private static final String CLIENT_ID = "my-app.apps.googleusercontent.com";
    private static final JsonFactory JSON = GsonFactory.getDefaultInstance();

    private static KeyPair googleKey;       // the key "Google" signs with (its certificate is served by the mock transport)
    private static KeyPair attackerKey;     // a different key: tokens signed with it are forgeries
    private static String certsJson;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        googleKey = gen.generateKeyPair();
        attackerKey = gen.generateKeyPair();

        X500Name name = new X500Name("CN=test-google");
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                new Date(System.currentTimeMillis() - 60_000), new Date(System.currentTimeMillis() + 3_600_000),
                name, googleKey.getPublic());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(new JcaContentSignerBuilder("SHA256withRSA").build(googleKey.getPrivate())));
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        certsJson = "{\"test-key-id\":\"" + pem.replace("\n", "\\n") + "\"}";
    }

    /** A response body can only be read once, so every verifier gets its own transport. */
    private static HttpTransport transport() {
        return new MockHttpTransport.Builder()
                .setLowLevelHttpResponse(new MockLowLevelHttpResponse().setContentType(Json.MEDIA_TYPE).setContent(certsJson))
                .build();
    }

    private static HttpTransport brokenTransport(String body) {
        return new MockHttpTransport.Builder()
                .setLowLevelHttpResponse(new MockLowLevelHttpResponse().setContentType(Json.MEDIA_TYPE).setContent(body))
                .build();
    }

    private static GoogleApiTokenVerifier verifier() {
        return new GoogleApiTokenVerifier(CLIENT_ID, transport(), JSON);
    }

    private static String token(KeyPair signWith, Consumer<GoogleIdToken.Payload> tweak) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setIssuer("https://accounts.google.com");
        payload.setAudience(CLIENT_ID);
        payload.setSubject("google-sub-123");
        payload.setIssuedAtTimeSeconds(now);
        payload.setExpirationTimeSeconds(now + 3600);
        payload.setEmail("ann@example.com");
        payload.setEmailVerified(true);
        payload.set("name", "Ann Example");
        payload.set("picture", "https://example.com/ann.png");
        payload.set("nonce", "nonce-1");
        tweak.accept(payload);
        JsonWebSignature.Header header = new JsonWebSignature.Header().setAlgorithm("RS256").setType("JWT").setKeyId("test-key-id");
        return JsonWebSignature.signUsingRsaSha256(signWith.getPrivate(), JSON, header, payload);
    }

    @Test
    void acceptsAValidTokenAndExtractsTheIdentity() throws Exception {
        GoogleIdentity id = verifier().verify(token(googleKey, p -> { }));
        assertEquals("google-sub-123", id.sub());
        assertEquals("ann@example.com", id.email());
        assertTrue(id.emailVerified());
        assertEquals("Ann Example", id.name());
        assertEquals("https://example.com/ann.png", id.picture());
        assertEquals("nonce-1", id.nonce());
    }

    @Test
    void acceptsTheAlternativeGoogleIssuerSpelling() throws Exception {
        verifier().verify(token(googleKey, p -> p.setIssuer("accounts.google.com")));
    }

    @Test
    void rejectsAForgedToken() throws Exception {
        String forged = token(attackerKey, p -> { });                   // perfect payload, wrong signing key
        assertThrows(UnauthorizedException.class, () -> verifier().verify(forged));
    }

    @Test
    void rejectsATokenWithTamperedPayload() throws Exception {
        String valid = token(googleKey, p -> { });
        String[] parts = valid.split("\\.");
        String evil = new String(Base64.getUrlDecoder().decode(parts[1])).replace("google-sub-123", "admin-sub-999");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(evil.getBytes()) + "." + parts[2];
        assertThrows(UnauthorizedException.class, () -> verifier().verify(tampered));
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        long twoHoursAgo = System.currentTimeMillis() / 1000 - 7200;
        String expired = token(googleKey, p -> { p.setIssuedAtTimeSeconds(twoHoursAgo - 3600); p.setExpirationTimeSeconds(twoHoursAgo); });
        assertThrows(UnauthorizedException.class, () -> verifier().verify(expired));
    }

    @Test
    void rejectsATokenForAnotherApp() throws Exception {
        String otherApp = token(googleKey, p -> p.setAudience("someone-elses-app.apps.googleusercontent.com"));
        assertThrows(UnauthorizedException.class, () -> verifier().verify(otherApp));
    }

    @Test
    void rejectsAWrongIssuer() throws Exception {
        String wrongIssuer = token(googleKey, p -> p.setIssuer("https://evil.example.com"));
        assertThrows(UnauthorizedException.class, () -> verifier().verify(wrongIssuer));
    }

    @Test
    void rejectsGarbage() {
        for (String bad : new String[]{"not-a-jwt", "a.b.c", "", "   ", null}) {
            assertThrows(UnauthorizedException.class, () -> verifier().verify(bad), "should reject: " + bad);
        }
    }

    @Test
    void reportsUnverifiedEmailAsSuch() throws Exception {
        GoogleIdentity id = verifier().verify(token(googleKey, p -> p.setEmailVerified(false)));
        assertFalse(id.emailVerified());
    }

    @Test
    void missingOptionalClaimsAreNull() throws Exception {
        GoogleIdentity id = verifier().verify(token(googleKey, p -> { p.set("name", null); p.set("picture", null); p.set("nonce", null); }));
        assertNull(id.name());
        assertNull(id.picture());
        assertNull(id.nonce());
    }

    @Test
    void unusableGoogleKeysAreReportedAsUnavailableNotAsABadToken() throws Exception {
        String valid = token(googleKey, p -> { });
        for (String body : new String[]{"[]", "not json at all"}) {
            GoogleApiTokenVerifier v = new GoogleApiTokenVerifier(CLIENT_ID, brokenTransport(body), JSON);
            assertThrows(ServiceUnavailableException.class, () -> v.verify(valid), "keys body: " + body);
        }
    }

    @Test
    void withoutAClientIdSignInIsReportedAsNotConfigured() throws Exception {
        String valid = token(googleKey, p -> { });
        for (String blank : new String[]{"", "   ", null}) {
            GoogleApiTokenVerifier unconfigured = new GoogleApiTokenVerifier(blank, transport(), JSON);
            ServiceUnavailableException e = assertThrows(ServiceUnavailableException.class, () -> unconfigured.verify(valid));
            assertEquals("Google sign-in is not configured", e.getMessage());
        }
    }
}
