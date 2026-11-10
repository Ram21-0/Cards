package com.cards.config;

import com.cards.service.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticates a request from its session token (cookie or Bearer). An unknown or expired token simply leaves the
 * request anonymous — endpoints that need a player then answer 401 through the entry point.
 *
 * CSRF defence for cookie sessions: SameSite=Lax cookies are not sent on cross-site POSTs, and on top of that a
 * state-changing request that is authenticated BY COOKIE to one of {@link #HEADER_PROTECTED} must carry an
 * X-Requested-With header (a browser will not add it on a cross-origin form or simple request).
 * Bearer-token clients are not affected.
 */
public class SessionAuthFilter extends OncePerRequestFilter {

    /** Paths whose state-changing requests need the header when sent with a cookie. */
    private static final Set<String> HEADER_PROTECTED = Set.of("/api/me", "/api/auth/logout", "/api/games");
    /** /api/games/{code}/(join|cpu|leave|start|call|choose) and /api/games/{code}/seats/{n} */
    private static final Pattern GAME_ACTION = Pattern.compile("^/api/games/[^/]+/(join|cpu|leave|start|call|choose|seats/[^/]+)$");

    static boolean requiresHeader(String path) {
        return HEADER_PROTECTED.contains(path) || GAME_ACTION.matcher(path).matches();
    }
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final SessionService sessions;
    private final SessionCookies cookies;

    public SessionAuthFilter(SessionService sessions, SessionCookies cookies) {
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SessionCookies.Presented presented = cookies.extract(request);
        if (presented != null) {
            sessions.authenticate(presented.token())
                    .ifPresent(player -> SecurityContextHolder.getContext().setAuthentication(new SessionAuthentication(player)));

            if (presented.viaCookie() && !SAFE_METHODS.contains(request.getMethod())
                    && requiresHeader(request.getRequestURI())
                    && request.getHeader("X-Requested-With") == null) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Missing X-Requested-With header\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
