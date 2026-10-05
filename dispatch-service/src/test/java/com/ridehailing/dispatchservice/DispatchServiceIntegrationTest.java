package com.ridehailing.dispatchservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.ridehailing.dispatchservice.domain.Trip;
import com.ridehailing.dispatchservice.domain.TripStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class DispatchServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("dispatch")
        .withUsername("dispatch_app")
        .withPassword("dispatch_pass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--requirepass", "redis_pass");

    static WireMockServer pricingService;
    static WireMockServer paymentService;
    static WireMockServer locationService;
    static WireMockServer wsGateway;

    static {
        pricingService = new WireMockServer(0);
        pricingService.start();

        paymentService = new WireMockServer(0);
        paymentService.start();

        locationService = new WireMockServer(0);
        locationService.start();

        wsGateway = new WireMockServer(0);
        wsGateway.start();
    }

    @LocalServerPort
    int port;

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("SERVER_PORT", () -> 0);
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PASSWORD", () -> "redis_pass");
        registry.add("INTERNAL_KEY", () -> "test-internal-key");
        registry.add("JWT_SECRET", () -> "test-jwt-secret");

        registry.add("PRICING_SERVICE_URL", () -> "http://localhost:" + pricingService.port());
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:" + paymentService.port());
        registry.add("LOCATION_SERVICE_URL", () -> "http://localhost:" + locationService.port());
        registry.add("WS_GATEWAY_URL", () -> "http://localhost:" + wsGateway.port());

        // Disable virtual threads for tests to avoid issues with WireMock
        registry.add("spring.threads.virtual.enabled", () -> "false");
    }

    @BeforeAll
    static void setupWireMock() {
        // WireMock servers already started in static block
    }

    @AfterAll
    static void teardownWireMock() {
        if (pricingService != null) pricingService.stop();
        if (paymentService != null) paymentService.stop();
        if (locationService != null) locationService.stop();
        if (wsGateway != null) wsGateway.stop();
    }

    @BeforeEach
    void cleanupDatabase() {
        jdbcClient.sql("DELETE FROM outbox").update();
        jdbcClient.sql("DELETE FROM offers").update();
        jdbcClient.sql("DELETE FROM trip_events").update();
        jdbcClient.sql("DELETE FROM trips").update();

        // Reset WireMock stubs - note: stubs must be configured AFTER this in each test
        pricingService.resetAll();
        paymentService.resetAll();
        locationService.resetAll();
        wsGateway.resetAll();
    }

    @Test
    void testCreateRide_Success() throws Exception {
        long customerId = 1001L;
        String idempotencyKey = UUID.randomUUID().toString();

        // Mock pricing service - accept any request to /internal/quote
        pricingService.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/quote"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"distanceM\":5000,\"fare\":50000,\"surge\":1.20}")));

        // Mock payment service
        paymentService.stubFor(WireMock.get(WireMock.urlEqualTo("/internal/wallets/" + customerId + "/balance"))
            .withHeader("X-Internal-Key", WireMock.equalTo("test-internal-key"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "balance": 100000
                    }
                """)));

        // Mock ws-gateway (best-effort, won't fail if it errors)
        wsGateway.stubFor(WireMock.post(WireMock.urlMatching("/internal/notify/.*"))
            .willReturn(WireMock.aResponse().withStatus(200)));

        Map<String, Object> request = Map.of(
            "pickupLat", 10.762622,
            "pickupLng", 106.660172,
            "dropoffLat", 10.772622,
            "dropoffLng", 106.670172,
            "idempotencyKey", idempotencyKey
        );

        webTestClient.post()
            .uri("/api/v1/rides")
            .header("X-User-Id", String.valueOf(customerId))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.tripId").isNotEmpty()
            .jsonPath("$.status").isEqualTo("MATCHING")
            .jsonPath("$.fare").isEqualTo(50000);

        // Verify trip in database
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM trips WHERE customer_id = ?")
            .param(customerId)
            .query(Integer.class)
            .single();
        assertThat(count).isEqualTo(1);

        // Verify outbox has events
        Integer eventCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox")
            .query(Integer.class)
            .single();
        assertThat(eventCount).isGreaterThanOrEqualTo(1);

        // Verify external service calls
        pricingService.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/internal/quote")));
        paymentService.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/internal/wallets/" + customerId + "/balance")));
    }

    @Test
    void testCreateRide_Idempotency() throws Exception {
        long customerId = 1002L;
        String idempotencyKey = UUID.randomUUID().toString();

        pricingService.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/quote"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"distanceM": 5000, "fare": 50000, "surge": 1.00}
                """)));

        paymentService.stubFor(WireMock.get(WireMock.urlMatching("/internal/wallets/.*/balance"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"balance": 100000}
                """)));

        wsGateway.stubFor(WireMock.post(WireMock.urlMatching("/internal/notify/.*"))
            .willReturn(WireMock.aResponse().withStatus(200)));

        Map<String, Object> request = Map.of(
            "pickupLat", 10.762622,
            "pickupLng", 106.660172,
            "dropoffLat", 10.772622,
            "dropoffLng", 106.670172,
            "idempotencyKey", idempotencyKey
        );

        // First request
        String firstResponse = webTestClient.post()
            .uri("/api/v1/rides")
            .header("X-User-Id", String.valueOf(customerId))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

        // Extract trip ID from first response
        UUID firstTripId = UUID.fromString(
            objectMapper.readTree(firstResponse).get("tripId").asText()
        );

        // Second request with same idempotency key - should return same trip
        String secondResponse = webTestClient.post()
            .uri("/api/v1/rides")
            .header("X-User-Id", String.valueOf(customerId))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

        // Extract trip ID from second response
        UUID secondTripId = UUID.fromString(
            objectMapper.readTree(secondResponse).get("tripId").asText()
        );

        // Both should return the same trip ID
        assertThat(secondTripId).isEqualTo(firstTripId);

        // Only one trip should exist
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM trips WHERE customer_id = ?")
            .param(customerId)
            .query(Integer.class)
            .single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void testCreateRide_InsufficientBalance() {
        long customerId = 1003L;
        String idempotencyKey = UUID.randomUUID().toString();

        pricingService.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/quote"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"distanceM": 5000, "fare": 50000, "surge": 1.00}
                """)));

        // Mock insufficient balance
        paymentService.stubFor(WireMock.get(WireMock.urlMatching("/internal/wallets/.*/balance"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"balance": 10000}
                """)));

        Map<String, Object> request = Map.of(
            "pickupLat", 10.762622,
            "pickupLng", 106.660172,
            "dropoffLat", 10.772622,
            "dropoffLng", 106.670172,
            "idempotencyKey", idempotencyKey
        );

        webTestClient.post()
            .uri("/api/v1/rides")
            .header("X-User-Id", String.valueOf(customerId))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isEqualTo(402)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INSUFFICIENT_BALANCE");

        // No trip should be created
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM trips WHERE customer_id = ?")
            .param(customerId)
            .query(Integer.class)
            .single();
        assertThat(count).isEqualTo(0);
    }

    @Test
    void testGetRide_Success() {
        long customerId = 1004L;
        UUID tripId = UUID.randomUUID();

        // Insert trip directly
        jdbcClient.sql("""
            INSERT INTO trips (id, customer_id, driver_id, status, pickup_lat, pickup_lng,
                               dropoff_lat, dropoff_lng, distance_m, fare, surge, idempotency_key,
                               version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
        """)
            .param(tripId)
            .param(customerId)
            .param(null)
            .param("MATCHING")
            .param(10.762622)
            .param(106.660172)
            .param(10.772622)
            .param(106.670172)
            .param(5000L)
            .param(50000L)
            .param(new BigDecimal("1.20"))
            .param("test-key")
            .param(0)
            .update();

        webTestClient.get()
            .uri("/api/v1/rides/{id}", tripId)
            .header("X-User-Id", String.valueOf(customerId))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.tripId").isEqualTo(tripId.toString())
            .jsonPath("$.customerId").isEqualTo(customerId)
            .jsonPath("$.status").isEqualTo("MATCHING")
            .jsonPath("$.fare").isEqualTo(50000);
    }

    @Test
    void testGetRide_NotFound() {
        UUID nonExistentTripId = UUID.randomUUID();

        webTestClient.get()
            .uri("/api/v1/rides/{id}", nonExistentTripId)
            .header("X-User-Id", "1005")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void testGetRide_WrongCustomer() {
        long customerId = 1006L;
        long otherCustomerId = 1007L;
        UUID tripId = UUID.randomUUID();

        jdbcClient.sql("""
            INSERT INTO trips (id, customer_id, driver_id, status, pickup_lat, pickup_lng,
                               dropoff_lat, dropoff_lng, distance_m, fare, surge, idempotency_key,
                               version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
        """)
            .param(tripId)
            .param(customerId)
            .param(null)
            .param("MATCHING")
            .param(10.762622)
            .param(106.660172)
            .param(10.772622)
            .param(106.670172)
            .param(5000L)
            .param(50000L)
            .param(new BigDecimal("1.00"))
            .param("test-key")
            .param(0)
            .update();

        // Try to access with wrong customer ID
        webTestClient.get()
            .uri("/api/v1/rides/{id}", tripId)
            .header("X-User-Id", String.valueOf(otherCustomerId))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }
}
