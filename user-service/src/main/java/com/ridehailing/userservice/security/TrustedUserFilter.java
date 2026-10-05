package com.ridehailing.userservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TrustedUserFilter extends OncePerRequestFilter {

    private final String internalKey;

    public TrustedUserFilter(@Value("${INTERNAL_KEY}") String internalKey) {
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalStateException("INTERNAL_KEY must be set");
        }
        this.internalKey = internalKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Only apply to /api/v1/users/me and /api/v1/drivers/me/**
        if (path.equals("/api/v1/users/me") || path.startsWith("/api/v1/drivers/me/")) {
            String providedKey = request.getHeader("X-Internal-Key");
            if (!internalKey.equals(providedKey)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-Internal-Key\"}");
                return;
            }

            String userIdHeader = request.getHeader("X-User-Id");
            String userRoleHeader = request.getHeader("X-User-Role");

            if (userIdHeader == null || userIdHeader.isBlank() || userRoleHeader == null || userRoleHeader.isBlank()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing X-User-Id or X-User-Role\"}");
                return;
            }

            // Store in request attributes for controllers to use
            request.setAttribute("X-User-Id", userIdHeader);
            request.setAttribute("X-User-Role", userRoleHeader);
        }

        filterChain.doFilter(request, response);
    }
}