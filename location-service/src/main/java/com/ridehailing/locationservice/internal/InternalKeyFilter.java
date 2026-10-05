package com.ridehailing.locationservice.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    private final String expectedKey;

    public InternalKeyFilter(@Value("${INTERNAL_KEY}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Only protect /internal/** paths
        if (path.startsWith("/internal/")) {
            String providedKey = request.getHeader("X-Internal-Key");

            if (providedKey == null || !providedKey.equals(expectedKey)) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-Internal-Key\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}