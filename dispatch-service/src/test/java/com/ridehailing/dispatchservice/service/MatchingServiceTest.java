package com.ridehailing.dispatchservice.service;

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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MatchingServiceTest {

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
    MatchingService matchingService;

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
    void testMatchLoop_FirstDriverTimeout_SecondDriverAccepts() throws Exception {
        UUID tripId = UUID.randomUUID();
        long customerId = 1001L;
        long driver1 = 2001L;
        long driver2 = 2002L;

        // Create trip in MATCHING state
        insertTripMatching(tripId, customerId);

        // Mock location service returning 2 drivers
        locationService.stubFor(WireMock.get(WireMock.urlMatching("/internal/drivers/nearby.*"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    [
                        {"driverId": %d, "lat": 10.763, "lng": 106.661, "distanceM": 500},
                        {"driverId": %d, "lat": 10.764, "lng": 106.662, "distanceM": 800}
                    ]
                """.formatted(driver1, driver2))));

        // Mock WebSocket notifications
        wsGateway.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/push"))
            .willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"delivered\": true}")));

        // Start matching
        matchingService.startMatching(tripId);

        // Wait for first offer
        await().atMost(5, SECONDS).until(() -> {
            String status = jdbcClient.sql("SELECT status FROM offers WHERE trip_id = ? AND driver_id = ?")
                .param(tripId).param(driver1)
                .query(String.class).optional().orElse(null);
            return "OFFERED".equals(status);
        });

        // Verify first driver has lock
        String lock1 = redisTemplate.opsForValue().get("disp:lock:" + driver1);
        assertThat(lock1).isEqualTo(tripId.toString());

        // Wait for first offer to timeout (15 seconds)
        await().atMost(18, SECONDS).until(() -> {
            String status = jdbcClient.sql("SELECT status FROM offers WHERE trip_id = ? AND driver_id = ?")
                .param(tripId).param(driver1)
                .query(String.class).optional().orElse(null);
            return "EXPIRED".equals(status);
        });

        // Verify first driver lock released
        String lock1After = redisTemplate.opsForValue().get("disp:lock:" + driver1);
        assertThat(lock1After).isNull();

        // Wait for second offer
        await().atMost(5, SECONDS).until(() -> {
            String status = jdbcClient.sql("SELECT status FROM offers WHERE trip_id = ? AND driver_id = ?")
                .param(tripId).param(driver2)
                .query(String.class).optional().orElse(null);
            return "OFFERED".equals(status);
        });

        // Verify second driver has lock
        String lock2 = redisTemplate.opsForValue().get("disp:lock:" + driver2);
        assertThat(lock2).isEqualTo(tripId.toString());

        // Verify first driver not offered again
        Integer driver1OfferCount = jdbcClient.sql("SELECT COUNT(*) FROM offers WHERE trip_id = ? AND driver_id = ?")
            .param(tripId).param(driver1)
            .query(Integer.class).single();
        assertThat(driver1OfferCount).isEqualTo(1);
    }

    @Test
    void testMatchLoop_NoCandidates_TransitionsToNoDriverFound() throws Exception {
        UUID tripId = UUID.randomUUID();
        long customerId = 1002L;

        // Create trip in MATCHING state
        insertTripMatching(tripId, customerId);

        // Mock location service returning no drivers
        locationService.stubFor(WireMock.get(WireMock.urlMatching("/internal/drivers/nearby.*"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[]")));

        // Mock WebSocket notification
        wsGateway.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/push"))
            .willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"delivered\": true}")));

        // Start matching
        matchingService.startMatching(tripId);

        // Wait for trip to transition to NO_DRIVER_FOUND
        await().atMost(3, SECONDS).until(() -> {
            String status = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
                .param(tripId)
                .query(String.class).optional().orElse(null);
            return "NO_DRIVER_FOUND".equals(status);
        });

        // Verify WS notification sent to customer
        wsGateway.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/internal/push")));
    }

    @Test
    void testMatchLoop_LocationServiceError_TransitionsToNoDriverFound() throws Exception {
        UUID tripId = UUID.randomUUID();
        long customerId = 1003L;

        // Create trip in MATCHING state
        insertTripMatching(tripId, customerId);

        // Mock location service error
        locationService.stubFor(WireMock.get(WireMock.urlMatching("/internal/drivers/nearby.*"))
            .willReturn(WireMock.aResponse().withStatus(500)));

        // Mock WebSocket notification
        wsGateway.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/push"))
            .willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"delivered\": true}")));

        // Start matching
        matchingService.startMatching(tripId);

        // Wait for trip to transition to NO_DRIVER_FOUND
        await().atMost(3, SECONDS).until(() -> {
            String status = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
                .param(tripId)
                .query(String.class).optional().orElse(null);
            return "NO_DRIVER_FOUND".equals(status);
        });
    }

    @Test
    void testMatchLoop_CustomerCancels_StopsAndReleasesLock() throws Exception {
        UUID tripId = UUID.randomUUID();
        long customerId = 1004L;
        long driverId = 2003L;

        // Create trip in MATCHING state
        insertTripMatching(tripId, customerId);

        // Mock location service returning one driver
        locationService.stubFor(WireMock.get(WireMock.urlMatching("/internal/drivers/nearby.*"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    [
                        {"driverId": %d, "lat": 10.763, "lng": 106.661, "distanceM": 500}
                    ]
                """.formatted(driverId))));

        // Mock WebSocket notification
        wsGateway.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/push"))
            .willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"delivered\": true}")));

        // Start matching
        matchingService.startMatching(tripId);

        // Wait for offer
        await().atMost(5, SECONDS).until(() -> {
            String status = jdbcClient.sql("SELECT status FROM offers WHERE trip_id = ? AND driver_id = ?")
                .param(tripId).param(driverId)
                .query(String.class).optional().orElse(null);
            return "OFFERED".equals(status);
        });

        // Verify lock acquired
        String lock = redisTemplate.opsForValue().get("disp:lock:" + driverId);
        assertThat(lock).isEqualTo(tripId.toString());

        // Customer cancels
        jdbcClient.sql("UPDATE trips SET status = 'CANCELLED', updated_at = now() WHERE id = ?")
            .param(tripId).update();

        // Wait for lock to be released
        await().atMost(3, SECONDS).until(() -> {
            String currentLock = redisTemplate.opsForValue().get("disp:lock:" + driverId);
            return currentLock == null;
        });
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
}
