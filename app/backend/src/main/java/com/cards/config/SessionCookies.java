package com.cards.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads and writes the session cookie. HttpOnly (JavaScript cannot read it), SameSite=Lax, Secure in production,
 * and a "__Host-" name prefix in production (which browsers only accept as Secure, Path=/, no Domain).
 * The same token is also accepted as "Authorization: Bearer ..." for non-browser clients.
 */
@Component
public class SessionCookies {

    /** Where a request's session token came from. */
    public record Presented(String token, boolean viaCookie) {
    }

    private final String name;
    private final boolean secure;
    private final Clock clock;

    public SessionCookies(@Value("${app.session.cookie-name:session}") String name,
                          @Value("${app.session.cookie-secure:false}") boolean secure,
                          Clock clock) {
        this.name = name;
        this.secure = secure;
        this.clock = clock;
    }

    /** A Bearer header wins over the cookie. Returns null when the request carries no token. */
    public Presented extract(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            if (!token.isEmpty()) return new Presented(token, false);
        }
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    return new Presented(c.getValue(), true);
                }
            }
        }
        return null;
    }

    /** The Set-Cookie header value that starts a session. */
    public String create(String token, Instant expiresAt) {
        Duration maxAge = Duration.between(clock.instant(), expiresAt);
        return base(token).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build().toString();
    }

    /** The Set-Cookie header value that removes the cookie. */
    public String clear() {
        return base("").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite("Lax").path("/");
    }
}
