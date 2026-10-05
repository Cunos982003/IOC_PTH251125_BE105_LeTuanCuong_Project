package com.ridehailing.wsgateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Component
public class JwtVerifier {
    private final byte[] secretKey;

    public JwtVerifier(@Value("${jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured");
        }
        this.secretKey = Base64.getDecoder().decode(secret);
    }

    public Map<String, Object> verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new SecurityException("Invalid token format");
        }

        String header = parts[0];
        String payload = parts[1];
        String signature = parts[2];

        String data = header + "." + payload;
        String expectedSig = sign(data);
        if (!expectedSig.equals(signature)) {
            throw new SecurityException("Invalid signature");
        }

        String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        return parseJson(json);
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, Map.class);
        } catch (Exception e) {
            throw new SecurityException("Failed to parse token payload", e);
        }
    }
}
