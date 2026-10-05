package com.ridehailing.wsgateway.security;

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
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

    private final String internalKey;

    public InternalKeyFilter(@Value("${internal.key}") String internalKey) {
        this.internalKey = internalKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (path.startsWith("/internal/")) {
            String providedKey = request.getHeader(INTERNAL_KEY_HEADER);

            if (providedKey == null || !providedKey.equals(internalKey)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Invalid or missing internal key\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
