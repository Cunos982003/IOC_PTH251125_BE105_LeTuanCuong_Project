package com.ridehailing.apigateway.filter;

import com.ridehailing.apigateway.config.GatewayConfig;
import com.ridehailing.apigateway.security.JwtVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@Order(1)
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/v1/auth/login",
        "/api/v1/auth/register"
    );

    private static final Set<String> DRIVER_ONLY_PATHS = Set.of(
        "/api/v1/trips/arrive",
        "/api/v1/trips/start",
        "/api/v1/trips/complete"
    );

    private final JwtVerifier jwtVerifier;
    private final GatewayConfig gatewayConfig;

    public AuthenticationFilter(JwtVerifier jwtVerifier, GatewayConfig gatewayConfig) {
        this.jwtVerifier = jwtVerifier;
        this.gatewayConfig = gatewayConfig;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = java.util.UUID.randomUUID().toString();
        }
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        if (path.equals("/internal") || path.startsWith("/internal/")) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "Resource not found");
            return;
        }

        // Health probe luôn public để host/load-balancer kiểm tra liveness từ bên ngoài.
        if ("GET".equals(method) && "/api/v1/health".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isPublicPath(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract and verify JWT
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid authorization header");
            return;
        }

        String token = authHeader.substring(7);
        Long userId;
        String role;

        try {
            JwtVerifier.Identity identity = jwtVerifier.verify(token);
            userId = identity.userId();
            role = identity.role();
        } catch (Exception e) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Invalid or expired token");
            return;
        }

        // Role-based authorization
        if (method.equals("POST") && path.equals("/api/v1/rides") && !"CUSTOMER".equals(role)) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Only customers can create rides");
            return;
        }

        if (DRIVER_ONLY_PATHS.stream().anyMatch(path::startsWith) && !"DRIVER".equals(role)) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Only drivers can access this endpoint");
            return;
        }

        // Set user attributes for downstream use
        request.setAttribute("userId", userId);
        request.setAttribute("userRole", role);
        request.setAttribute("internalKey", gatewayConfig.getInternalKey());

        filterChain.doFilter(request, response);
    }

    private boolean isPublicPath(String path, String method) {
        return "POST".equals(method) && PUBLIC_PATHS.contains(path);
    }

    private void sendError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(String.format("{\"code\":\"%s\",\"message\":\"%s\"}", code, message));
    }
}
