package com.ridehailing.dispatchservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InternalTripControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("dispatch")
        .withUsername("dispatch_app")
        .withPassword("dispatch_pass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--requirepass", "redis_pass");

    static WireMockServer locationService;
    static WireMockServer wsGateway;

    static {
        locationService = new WireMockServer(0);
        locationService.start();

        wsGateway = new WireMockServer(0);
        wsGateway.start();
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    RedisTemplate<String, String> redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("SERVER_PORT", () -> 0);
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", redis::getFirstMappedPort);
        registry.add("REDIS_PASSWORD", () -> "redis_pass");
        registry.add("INTERNAL_KEY", () -> "test-internal-key");
        registry.add("JWT_SECRET", () -> "test-jwt-secret");
        registry.add("PRICING_SERVICE_URL", () -> "http://localhost:8084");
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:8085");
        registry.add("LOCATION_SERVICE_URL", () -> "http://localhost:" + locationService.port());
        registry.add("WS_GATEWAY_URL", () -> "http://localhost:" + wsGateway.port());
    }

    @BeforeAll
    static void setupWireMock() {
        // WireMock servers already started in static block
    }

    @AfterAll
    static void teardownWireMock() {
        if (locationService != null) locationService.stop();
        if (wsGateway != null) wsGateway.stop();
    }

    @BeforeEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM outbox").update();
        jdbcClient.sql("DELETE FROM offers").update();
        jdbcClient.sql("DELETE FROM trip_events").update();
        jdbcClient.sql("DELETE FROM trips").update();

        // Clear Redis locks
        redisTemplate.keys("disp:lock:*").forEach(key -> redisTemplate.delete(key));

        locationService.resetAll();
        wsGateway.resetAll();
    }

    @Test
    void testAcceptTrip_Success() {
        UUID tripId = UUID.randomUUID();
        long customerId = 1001L;
        long driverId = 2001L;

        // Create trip in MATCHING state
        insertTripMatching(tripId, customerId);

        // Create offer
        insertOffer(tripId, driverId, "OFFERED");

        // Set Redis lock
        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripId.toString(), Duration.ofSeconds(20));

        // Mock location service
        locationService.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/drivers/" + driverId + "/busy"))
            .willReturn(WireMock.aResponse().withStatus(200)));

        // Mock WebSocket notification
        wsGateway.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/push"))
            .willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"delivered\": true}")));

        // Accept trip
        webTestClient.post()
            .uri("/internal/trips/{tripId}/accept", tripId)
            .header("X-Internal-Key", "test-internal-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("driverId", driverId))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.tripId").isEqualTo(tripId.toString())
            .jsonPath("$.status").isEqualTo("ACCEPTED");

        // Verify trip updated
        String tripStatus = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
            .param(tripId)
            .query(String.class)
            .single();
        assertThat(tripStatus).isEqualTo("ACCEPTED");

        Long tripDriverId = jdbcClient.sql("SELECT driver_id FROM trips WHERE id = ?")
            .param(tripId)
            .query(Long.class)
            .single();
        assertThat(tripDriverId).isEqualTo(driverId);

        // Verify offer updated
        String offerStatus = jdbcClient.sql("SELECT status FROM offers WHERE trip_id = ? AND driver_id = ?")
            .param(tripId)
            .param(driverId)
            .query(String.class)
            .single();
        assertThat(offerStatus).isEqualTo("ACCEPTED");

        // Verify external calls
        locationService.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/internal/drivers/" + driverId + "/busy")));
        wsGateway.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/internal/push")));
    }

    @Test
    void testAcceptTrip_InvalidLock_Returns409() {
        UUID tripId = UUID.randomUUID();
        long customerId = 1002L;
        long driverId = 2002L;

        // Create trip in MATCHING state
        insertTripMatching(tripId, customerId);

        // Create offer
        insertOffer(tripId, driverId, "OFFERED");

        // No Redis lock set (or wrong value)

        // Accept trip
        webTestClient.post()
            .uri("/internal/trips/{tripId}/accept", tripId)
            .header("X-Internal-Key", "test-internal-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("driverId", driverId))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_LOCK");

        // Verify trip not updated
        String tripStatus = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
            .param(tripId)
            .query(String.class)
            .single();
        assertThat(tripStatus).isEqualTo("MATCHING");
    }

    @Test
    void testAcceptTrip_TripNotMatching_Returns409() {
        UUID tripId = UUID.randomUUID();
        long customerId = 1003L;
        long driverId = 2003L;

        // Create trip in ACCEPTED state (not MATCHING)
        insertTripAccepted(tripId, customerId, driverId);

        // Set Redis lock
        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripId.toString(), Duration.ofSeconds(20));

        // Accept trip (should fail)
        webTestClient.post()
            .uri("/internal/trips/{tripId}/accept", tripId)
            .header("X-Internal-Key", "test-internal-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("driverId", driverId))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("TRIP_NOT_MATCHING");
    }

    @Test
    void testAcceptTrip_DriverAlreadyBusy_Returns409() {
        UUID tripId1 = UUID.randomUUID();
        UUID tripId2 = UUID.randomUUID();
        long customerId1 = 1004L;
        long customerId2 = 1005L;
        long driverId = 2004L;

        // Driver already has an active trip
        insertTripAccepted(tripId1, customerId1, driverId);

        // Create new trip for same driver
        insertTripMatching(tripId2, customerId2);
        insertOffer(tripId2, driverId, "OFFERED");

        // Set Redis lock for second trip
        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripId2.toString(), Duration.ofSeconds(20));

        // Try to accept second trip (should fail due to unique constraint)
        webTestClient.post()
            .uri("/internal/trips/{tripId}/accept", tripId2)
            .header("X-Internal-Key", "test-internal-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("driverId", driverId))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("DRIVER_ALREADY_BUSY");

        // Verify second trip still MATCHING
        String tripStatus = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
            .param(tripId2)
            .query(String.class)
            .single();
        assertThat(tripStatus).isEqualTo("MATCHING");
    }

    private void insertTripMatching(UUID tripId, long customerId) {
        Instant now = Instant.now();
        jdbcClient.sql("""
            INSERT INTO trips (id, customer_id, driver_id, status, pickup_lat, pickup_lng,
                               dropoff_lat, dropoff_lng, distance_m, fare, surge, idempotency_key,
                               version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            .param("test-key-" + tripId)
            .param(0)
            .param(java.sql.Timestamp.from(now))
            .param(java.sql.Timestamp.from(now))
            .update();
    }

    private void insertTripAccepted(UUID tripId, long customerId, long driverId) {
        Instant now = Instant.now();
        jdbcClient.sql("""
            INSERT INTO trips (id, customer_id, driver_id, status, pickup_lat, pickup_lng,
                               dropoff_lat, dropoff_lng, distance_m, fare, surge, idempotency_key,
                               version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
            .param(tripId)
            .param(customerId)
            .param(driverId)
            .param("ACCEPTED")
            .param(10.762622)
            .param(106.660172)
            .param(10.772622)
            .param(106.670172)
            .param(5000L)
            .param(50000L)
            .param(new BigDecimal("1.00"))
            .param("test-key-" + tripId)
            .param(0)
            .param(java.sql.Timestamp.from(now))
            .param(java.sql.Timestamp.from(now))
            .update();
    }

    private void insertOffer(UUID tripId, long driverId, String status) {
        jdbcClient.sql("""
            INSERT INTO offers (trip_id, driver_id, status, offered_at)
            VALUES (?, ?, ?, ?)
        """)
            .param(tripId)
            .param(driverId)
            .param(status)
            .param(java.sql.Timestamp.from(Instant.now()))
            .update();
    }
}
