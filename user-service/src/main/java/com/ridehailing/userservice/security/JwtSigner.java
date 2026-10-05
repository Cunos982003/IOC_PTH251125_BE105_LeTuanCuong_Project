package com.ridehailing.userservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

@Component
public class JwtSigner {

    private final SecretKey key;
    private final long expirationMs;

    public JwtSigner(@Value("${JWT_SECRET}") String secretBase64) {
        if (secretBase64 == null || secretBase64.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be set");
        }
        byte[] decoded = Base64.getDecoder().decode(secretBase64);
        if (decoded.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (base64 decoded)");
        }
        this.key = Keys.hmacShaKeyFor(decoded);
        this.expirationMs = 3600_000; // 1 hour
    }

    public String sign(Long userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token);
    }

    public Map<String, Object> getClaims(String token) {
        return parse(token).getPayload();
    }
}