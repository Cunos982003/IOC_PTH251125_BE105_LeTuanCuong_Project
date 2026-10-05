package com.ridehailing.apigateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ridehailing.apigateway.config.GatewayConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final Cache<String, AtomicInteger> authRateLimitCache;
    private final Cache<String, AtomicInteger> userRateLimitCache;
    private final GatewayConfig gatewayConfig;

    public RateLimitFilter(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;

        // Auth endpoints: rate limit by IP
        this.authRateLimitCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

        // Other endpoints: rate limit by user ID
        this.userRateLimitCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip rate limiting for internal paths (already blocked by auth filter)
        if (path.startsWith("/internal/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Rate limit auth endpoints by IP
        if (path.startsWith("/api/v1/auth/")) {
            String ip = getClientIp(request);
            AtomicInteger count = authRateLimitCache.get(ip, k -> new AtomicInteger(0));

            if (count.incrementAndGet() > gatewayConfig.getRateLimit().getAuthRpm()) {
                sendError(response, 429, "RATE_LIMIT_EXCEEDED", "Too many requests from this IP");
                return;
            }
        } else {
            // Rate limit other endpoints by user ID
            Object userId = request.getAttribute("userId");
            if (userId != null) {
                String key = "user:" + userId;
                AtomicInteger count = userRateLimitCache.get(key, k -> new AtomicInteger(0));

                if (count.incrementAndGet() > gatewayConfig.getRateLimit().getUserRpm()) {
                    sendError(response, 429, "RATE_LIMIT_EXCEEDED", "Too many requests");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private void sendError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(String.format("{\"code\":\"%s\",\"message\":\"%s\"}", code, message));
    }
}
