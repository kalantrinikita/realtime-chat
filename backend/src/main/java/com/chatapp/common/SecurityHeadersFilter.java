package com.chatapp.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Browser hardening headers on every response.
 *
 * <p>The CSP lets the page load only its own bundle, so even if an XSS bug slipped in, injected
 * inline script could not run (React already escapes all message text). ws:/wss: are listed
 * because older Safari versions don't treat 'self' as covering WebSocket URLs.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private static final String CSP = "default-src 'self'; connect-src 'self' ws: wss:; img-src 'self' data:; "
            + "style-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", CSP);
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
