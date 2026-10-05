package com.ridehailing.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@org.springframework.test.context.ActiveProfiles({"infra", "test"})
class DriverIntegrationTest {

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

    private String registerDriver() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email":"driver@example.com","password":"password123","fullName":"Driver User","role":"DRIVER"}
                """;
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/register", request, Map.class);
        return (String) response.getBody().get("accessToken");
    }

    private HttpHeaders authHeaders(String userId, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", "test-internal-key");
        headers.set("X-User-Id", userId);
        headers.set("X-User-Role", role);
        return headers;
    }

    @Test
    void driverUpdateStatus_online() {
        String token = registerDriver();
        // Decode token to get userId (simplified - in real test we'd parse JWT)
        // For now, we know the user ID will be 1 since it's the first user
        HttpHeaders headers = authHeaders("1", "DRIVER");

        String body = "{\"status\":\"ONLINE\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Void> response = restTemplate.exchange("/api/v1/drivers/me/status", HttpMethod.PUT, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void driverUpdateStatus_offline() {
        String token = registerDriver();
        HttpHeaders headers = authHeaders("1", "DRIVER");

        String body = "{\"status\":\"OFFLINE\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Void> response = restTemplate.exchange("/api/v1/drivers/me/status", HttpMethod.PUT, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void driverUpdateStatus_invalidStatus_returns400() {
        String token = registerDriver();
        HttpHeaders headers = authHeaders("1", "DRIVER");

        String body = "{\"status\":\"BUSY\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange("/api/v1/drivers/me/status", HttpMethod.PUT, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void customerCannotUpdateDriverStatus() {
        // Register customer
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email":"customer@example.com","password":"password123","fullName":"Customer User","role":"CUSTOMER"}
                """;
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/register", request, Map.class);

        HttpHeaders authHeaders = authHeaders("2", "CUSTOMER");
        String statusBody = "{\"status\":\"ONLINE\"}";
        HttpEntity<String> statusRequest = new HttpEntity<>(statusBody, authHeaders);
        ResponseEntity<Map> statusResponse = restTemplate.exchange("/api/v1/drivers/me/status", HttpMethod.PUT, statusRequest, Map.class);

        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void driverUpdateVehicle() {
        String token = registerDriver();
        HttpHeaders headers = authHeaders("1", "DRIVER");

        String body = "{\"plate\":\"ABC-123\",\"type\":\"SEDAN\",\"model\":\"Toyota Camry\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<Void> response = restTemplate.exchange("/api/v1/drivers/me/vehicle", HttpMethod.PUT, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void driverUpdateVehicle_duplicatePlate_returns409() {
        String token = registerDriver();
        HttpHeaders headers = authHeaders("1", "DRIVER");

        String body = "{\"plate\":\"ABC-123\",\"type\":\"SEDAN\",\"model\":\"Toyota Camry\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        restTemplate.exchange("/api/v1/drivers/me/vehicle", HttpMethod.PUT, request, Void.class);

        // Try to register another driver with same plate
        HttpHeaders headers2 = new HttpHeaders();
        headers2.setContentType(MediaType.APPLICATION_JSON);
        String body2 = """
                {"email":"driver2@example.com","password":"password123","fullName":"Driver 2","role":"DRIVER"}
                """;
        HttpEntity<String> request2 = new HttpEntity<>(body2, headers2);
        ResponseEntity<Map> regResponse = restTemplate.postForEntity("/api/v1/auth/register", request2, Map.class);

        // The second driver would have ID 2
        HttpHeaders headersDriver2 = authHeaders("2", "DRIVER");
        HttpEntity<String> vehicleRequest = new HttpEntity<>(body, headersDriver2);
        ResponseEntity<Map> response = restTemplate.exchange("/api/v1/drivers/me/vehicle", HttpMethod.PUT, vehicleRequest, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void userMe_returnsUserInfo() {
        String token = registerDriver();
        HttpHeaders headers = authHeaders("1", "DRIVER");

        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange("/api/v1/users/me", HttpMethod.GET, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object id = response.getBody().get("id");
        assertThat(id).isNotNull();
        assertThat(((Number) id).longValue()).isEqualTo(1L);
        assertThat(response.getBody()).containsEntry("role", "DRIVER");
        assertThat(response.getBody()).containsEntry("fullName", "Driver User");
        // Should not contain password_hash
        assertThat(response.getBody()).doesNotContainKey("passwordHash");
        assertThat(response.getBody()).doesNotContainKey("password_hash");
    }
}