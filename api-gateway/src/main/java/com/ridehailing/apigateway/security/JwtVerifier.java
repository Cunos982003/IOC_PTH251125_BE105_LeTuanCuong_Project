package com.ridehailing.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.util.Base64;
import java.util.Set;

public class JwtVerifier {

    private final JwtParser parser;

    public JwtVerifier(String jwtSecret) {
        parser = Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret)))
            .build();
    }

    public Identity verify(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();
        long userId = Long.parseLong(claims.getSubject());
        String role = claims.get("role", String.class);
        if (userId <= 0 || role == null || !Set.of("CUSTOMER", "DRIVER").contains(role)
                || claims.getExpiration() == null) {
            throw new IllegalArgumentException("Invalid identity claims");
        }
        return new Identity(userId, role);
    }

    public record Identity(long userId, String role) {}
}
