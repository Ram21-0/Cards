package com.cards.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses an oversized request body before it is read: 64 KB for the API, 2 MB under /api/admin/ (stat imports), 40 MB for deck uploads (CSV + images).
 * Judged by Content-Length; a body without one is bounded by the container's own limits.
 */
@Component
public class RequestSizeFilter extends OncePerRequestFilter {

    private final long apiLimit;
    private final long adminLimit;
    private final long uploadLimit;

    public RequestSizeFilter(@Value("${app.limits.api-body-bytes:65536}") long apiLimit, @Value("${app.limits.admin-body-bytes:2097152}") long adminLimit,
                             @Value("${app.limits.upload-body-bytes:41943040}") long uploadLimit) {
        this.apiLimit = apiLimit;
        this.adminLimit = adminLimit;
        this.uploadLimit = uploadLimit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean upload = path.startsWith("/api/decks/") && path.endsWith("/import");
        long limit = upload ? uploadLimit : path.startsWith("/api/admin/") ? adminLimit : path.startsWith("/api/") ? apiLimit : Long.MAX_VALUE;
        if (request.getContentLengthLong() > limit) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Request body too large\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
