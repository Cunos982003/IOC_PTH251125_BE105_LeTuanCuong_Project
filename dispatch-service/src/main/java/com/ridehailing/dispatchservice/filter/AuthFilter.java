package com.ridehailing.dispatchservice.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Skip auth for health probes and internal endpoints
        if (path.equals("/api/v1/health") || path.startsWith("/internal/") || path.startsWith("/actuator/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract X-User-Id for public API endpoints
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader == null || userIdHeader.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"MISSING_USER_ID\",\"message\":\"X-User-Id header required\"}");
            return;
        }

        try {
            long userId = Long.parseLong(userIdHeader);
            request.setAttribute("userId", userId);
            filterChain.doFilter(request, response);
        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"INVALID_USER_ID\",\"message\":\"X-User-Id must be a number\"}");
        }
    }
}
