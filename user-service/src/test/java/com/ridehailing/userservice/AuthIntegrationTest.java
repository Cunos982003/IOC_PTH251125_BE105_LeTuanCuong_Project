package com.ridehailing.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.userservice.event.UserRegisteredEvent;
import com.ridehailing.userservice.security.JwtSigner;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;

import javax.crypto.SecretKey;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@org.springframework.test.context.ActiveProfiles({"infra", "test"})
class AuthIntegrationTest {

    // Force Testcontainers to use host.docker.internal on Windows
    static {
        System.setProperty("testcontainers.use-hostname-resolution", "true");
    }

    @BeforeAll
    static void disableAuthenticator() {
        // Disable HttpURLConnection's automatic retry on 401 which causes HttpRetryException
        // when the request body has already been streamed
        Authenticator.setDefault(null);
        // Also disable NTLM retry
        System.setProperty("sun.net.http.auth.ntlm.retry", "false");
        System.setProperty("http.auth.preemptive", "false");
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("user")
            .withUsername("user_app")
            .withPassword("user_pass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(java.time.Duration.ofSeconds(120));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", redis::getFirstMappedPort);
        // No password for test Redis
        registry.add("JWT_SECRET", () -> Base64.getEncoder().encodeToString("this-is-a-very-long-secret-key-for-testing-purposes-only".getBytes()));
        registry.add("INTERNAL_KEY", () -> "test-internal-key");
        // Disable OutboxWorker scheduler in tests
        registry.add("outbox.scheduler.enabled", () -> "false");
        // HikariCP settings for Testcontainers on Windows
        registry.add("spring.datasource.hikari.connection-timeout", () -> "30000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "5000");
        registry.add("spring.datasource.hikari.idle-timeout", () -> "30000");
        registry.add("spring.datasource.hikari.max-lifetime", () -> "60000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-test-query", () -> "SELECT 1");
        // Lettuce settings for better resilience on Windows
        registry.add("spring.data.redis.lettuce.pool.max-active", () -> "8");
        registry.add("spring.data.redis.lettuce.pool.max-idle", () -> "8");
        registry.add("spring.data.redis.lettuce.pool.min-idle", () -> "2");
        registry.add("spring.data.redis.timeout", () -> "5000ms");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtSigner jwtSigner;

    @Test
    @Order(1)
    void registerAndLogin_success() {
        // Register
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email":"test@example.com","password":"password123","fullName":"Test User","role":"CUSTOMER"}
                """;
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> registerResponse = restTemplate.postForEntity("/api/v1/auth/register", request, Map.class);

        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(registerResponse.getBody()).containsKey("accessToken");
        String token = (String) registerResponse.getBody().get("accessToken");

        // Login
        String loginBody = """
                {"email":"test@example.com","password":"password123"}
                """;
        HttpEntity<String> loginRequest = new HttpEntity<>(loginBody, headers);
        ResponseEntity<Map> loginResponse = restTemplate.postForEntity("/api/v1/auth/login", loginRequest, Map.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).containsKey("accessToken");

        // Verify JWT can be decoded with same secret
        var claims = jwtSigner.getClaims(token);
        assertThat(claims.get("sub")).isNotNull();
        assertThat(claims.get("role")).isEqualTo("CUSTOMER");
    }

    @Test
    @Order(2)
    void register_duplicateEmail_returns409() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email":"dup@example.com","password":"password123","fullName":"Test User","role":"CUSTOMER"}
                """;
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        // First registration
        restTemplate.postForEntity("/api/v1/auth/register", request, Map.class);

        // Second registration with same email
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/register", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "CONFLICT");
    }

    @Test
    @Order(3)
    void login_wrongPassword_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email":"wrong@example.com","password":"password123","fullName":"Test User","role":"CUSTOMER"}
                """;
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        restTemplate.postForEntity("/api/v1/auth/register", request, Map.class);

        // Wrong password
        String loginBody = """
                {"email":"wrong@example.com","password":"wrongpassword"}
                """;
        HttpEntity<String> loginRequest = new HttpEntity<>(loginBody, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/login", loginRequest, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "UNAUTHORIZED");
    }

    @Test
    @Order(4)
    void login_nonexistentEmail_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String loginBody = """
                {"email":"nonexistent@example.com","password":"password123"}
                """;
        HttpEntity<String> loginRequest = new HttpEntity<>(loginBody, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/login", loginRequest, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "UNAUTHORIZED");
    }

    @Test
    @Order(5)
    void internalEndpoint_withoutKey_returns401() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/internal/users/1", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(6)
    void internalEndpoint_withKey_returnsUser() {
        // First register a user
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email":"internal@example.com","password":"password123","fullName":"Internal User","role":"DRIVER"}
                """;
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> registerResponse = restTemplate.postForEntity("/api/v1/auth/register", request, Map.class);

        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) registerResponse.getBody().get("accessToken");
        Long userId = extractUserIdFromToken(token);

        // Use the internal endpoint with key
        HttpHeaders internalHeaders = new HttpHeaders();
        internalHeaders.set("X-Internal-Key", "test-internal-key");
        HttpEntity<Void> internalRequest = new HttpEntity<>(internalHeaders);

        ResponseEntity<Map> response = restTemplate.exchange("/internal/users/" + userId, HttpMethod.GET, internalRequest, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Handle both Integer and Long (Jackson deserializes numbers as Integer by default)
        Object id = response.getBody().get("id");
        assertThat(id).isNotNull();
        assertThat(((Number) id).longValue()).isEqualTo(userId);
        assertThat(response.getBody()).containsEntry("role", "DRIVER");
        assertThat(response.getBody()).containsEntry("fullName", "Internal User");
    }

    private Long extractUserIdFromToken(String token) {
        String secret = "this-is-a-very-long-secret-key-for-testing-purposes-only";
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.valueOf(claims.getSubject());
    }

    @Test
    @Order(7)
    void userMe_withoutInternalKey_returns401() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/users/me", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(8)
    void userMe_withInternalKeyButNoUserId_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", "test-internal-key");
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange("/api/v1/users/me", HttpMethod.GET, request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        RestTemplateBuilder restTemplateBuilder() {
            return new RestTemplateBuilder()
                    .requestFactory(() -> new org.springframework.http.client.JdkClientHttpRequestFactory());
        }
    }
}

