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
public class InternalKeyFilter extends OncePerRequestFilter {

    private final String internalKey;

    public InternalKeyFilter(@Value("${INTERNAL_KEY}") String internalKey) {
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalStateException("INTERNAL_KEY must be set");
        }
        this.internalKey = internalKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (path.startsWith("/internal/")) {
            String providedKey = request.getHeader("X-Internal-Key");
            if (!internalKey.equals(providedKey)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-Internal-Key\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}