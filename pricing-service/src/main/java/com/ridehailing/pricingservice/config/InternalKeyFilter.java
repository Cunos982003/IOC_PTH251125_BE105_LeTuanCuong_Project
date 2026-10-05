package com.ridehailing.pricingservice.config;

import com.ridehailing.pricingservice.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Value("${internal.key}")
    private String internalKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path.startsWith("/internal/")) {
            String headerKey = request.getHeader("X-Internal-Key");
            if (headerKey == null || !headerKey.equals(internalKey)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write(objectMapper.writeValueAsString(
                    new ErrorResponse("FORBIDDEN", "Invalid or missing internal key")
                ));
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
