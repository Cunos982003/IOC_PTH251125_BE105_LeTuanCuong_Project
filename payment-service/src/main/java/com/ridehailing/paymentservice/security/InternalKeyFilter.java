package com.ridehailing.paymentservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    private final String internalKey;

    public InternalKeyFilter(@Value("${payment.internal-key}") String internalKey) {
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalStateException("INTERNAL_KEY must be set");
        }
        this.internalKey = internalKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean internalRequest = path.startsWith("/internal/");
        boolean walletRequest = path.equals("/api/v1/wallet") || path.startsWith("/api/v1/wallet/");

        if (internalRequest || walletRequest) {
            String providedKey = request.getHeader("X-Internal-Key");
            if (!internalKey.equals(providedKey)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-Internal-Key\"}");
                return;
            }
        }

        if (walletRequest) {
            String userId = request.getHeader("X-User-Id");
            if (userId == null || userId.isBlank()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing X-User-Id\"}");
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        }

        filterChain.doFilter(request, response);
    }
}
