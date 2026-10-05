package com.ridehailing.dispatchservice.worker;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DriverReconciliationJobTest {

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

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    RedisTemplate<String, String> redisTemplate;

    @Autowired
    DriverReconciliationJob reconciliationJob;

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
        registry.add("PRICING_SERVICE_URL", () -> "http://localhost:9999");
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:9999");
        registry.add("LOCATION_SERVICE_URL", () -> "http://localhost:" + locationService.port());
        registry.add("WS_GATEWAY_URL", () -> "http://localhost:" + wsGateway.port());
    }

    @BeforeAll
    static void setupWireMock() {
        locationService = new WireMockServer(0);
        locationService.start();

        wsGateway = new WireMockServer(0);
        wsGateway.start();
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

        Set<String> keys = redisTemplate.keys("disp:lock:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        locationService.resetAll();
        wsGateway.resetAll();
    }

    @Test
    void reconcileBusyDrivers_freesDriverWithCompletedTrip() {
        // Given: driver with COMPLETED trip in last 10 minutes
        long driverId = 2001L;
        UUID tripId = UUID.randomUUID();
        Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        insertTrip(tripId, 1001L, driverId, "COMPLETED", fiveMinutesAgo);

        // And: Redis lock still exists (cleanup didn't run)
        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripId.toString(), Duration.ofMinutes(10));

        // Mock location service
        locationService.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/drivers/" + driverId + "/free"))
            .willReturn(WireMock.aResponse().withStatus(200)));

        // Mock ws-gateway
        wsGateway.stubFor(WireMock.delete(WireMock.urlEqualTo("/internal/routes/" + driverId))
            .willReturn(WireMock.aResponse().withStatus(200)));

        // When: reconciliation runs
        reconciliationJob.reconcileBusyDrivers();

        // Then: location service called to free driver
        locationService.verify(WireMock.postRequestedFor(
            WireMock.urlEqualTo("/internal/drivers/" + driverId + "/free")));

        // And: ws-gateway called to delete route
        wsGateway.verify(WireMock.deleteRequestedFor(
            WireMock.urlEqualTo("/internal/routes/" + driverId)));

        // And: Redis lock deleted
        Boolean lockExists = redisTemplate.hasKey("disp:lock:" + driverId);
        assertThat(lockExists).isFalse();
    }

    @Test
    void reconcileBusyDrivers_doesNotFreeDriverWithActiveTrip() {
        // Given: driver with ACCEPTED trip (active)
        long driverId = 2001L;
        UUID tripId = UUID.randomUUID();
        insertTrip(tripId, 1001L, driverId, "ACCEPTED", Instant.now());

        // Mock services
        locationService.stubFor(WireMock.post(WireMock.urlMatching("/internal/drivers/.*/free"))
            .willReturn(WireMock.aResponse().withStatus(200)));
        wsGateway.stubFor(WireMock.delete(WireMock.urlMatching("/internal/routes/.*"))
            .willReturn(WireMock.aResponse().withStatus(200)));

        // When: reconciliation runs
        reconciliationJob.reconcileBusyDrivers();

        // Then: location service NOT called (driver still active)
        locationService.verify(0, WireMock.postRequestedFor(
            WireMock.urlMatching("/internal/drivers/.*/free")));
    }

    @Test
    void reconcileBusyDrivers_handlesLocationServiceFailureGracefully() {
        // Given: driver with COMPLETED trip
        long driverId = 2001L;
        UUID tripId = UUID.randomUUID();
        Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        insertTrip(tripId, 1001L, driverId, "COMPLETED", fiveMinutesAgo);

        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripId.toString(), Duration.ofMinutes(10));

        // Mock location service failure
        locationService.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/drivers/" + driverId + "/free"))
            .willReturn(WireMock.aResponse().withStatus(500)));

        // Mock ws-gateway success
        wsGateway.stubFor(WireMock.delete(WireMock.urlEqualTo("/internal/routes/" + driverId))
            .willReturn(WireMock.aResponse().withStatus(200)));

        // When: reconciliation runs (should not throw exception)
        reconciliationJob.reconcileBusyDrivers();

        // Then: ws-gateway still called despite location service failure
        wsGateway.verify(WireMock.deleteRequestedFor(
            WireMock.urlEqualTo("/internal/routes/" + driverId)));

        // And: Redis lock still deleted
        Boolean lockExists = redisTemplate.hasKey("disp:lock:" + driverId);
        assertThat(lockExists).isFalse();
    }

    @Test
    void reconcileBusyDrivers_retriesOnNextRun() {
        // Given: driver with COMPLETED trip, but cleanup failed previously
        long driverId = 2001L;
        UUID tripId = UUID.randomUUID();
        Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        insertTrip(tripId, 1001L, driverId, "COMPLETED", fiveMinutesAgo);

        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripId.toString(), Duration.ofMinutes(10));

        // First run: location service fails
        locationService.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/drivers/" + driverId + "/free"))
            .willReturn(WireMock.aResponse().withStatus(500)));
        wsGateway.stubFor(WireMock.delete(WireMock.urlEqualTo("/internal/routes/" + driverId))
            .willReturn(WireMock.aResponse().withStatus(200)));

        reconciliationJob.reconcileBusyDrivers();

        // Re-create lock (simulate partial failure - some cleanups didn't work)
        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripId.toString(), Duration.ofMinutes(10));

        // Second run: location service succeeds
        locationService.resetAll();
        locationService.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/drivers/" + driverId + "/free"))
            .willReturn(WireMock.aResponse().withStatus(200)));
        wsGateway.resetAll();
        wsGateway.stubFor(WireMock.delete(WireMock.urlEqualTo("/internal/routes/" + driverId))
            .willReturn(WireMock.aResponse().withStatus(200)));

        // When: second reconciliation run
        reconciliationJob.reconcileBusyDrivers();

        // Then: retried successfully
        locationService.verify(WireMock.postRequestedFor(
            WireMock.urlEqualTo("/internal/drivers/" + driverId + "/free")));

        Boolean lockExists = redisTemplate.hasKey("disp:lock:" + driverId);
        assertThat(lockExists).isFalse();
    }

    @Test
    void reconcileBusyDrivers_handlesMultipleDrivers() {
        // Given: 3 drivers with completed trips
        long driver1 = 2001L;
        long driver2 = 2002L;
        long driver3 = 2003L;

        Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        insertTrip(UUID.randomUUID(), 1001L, driver1, "COMPLETED", fiveMinutesAgo);
        insertTrip(UUID.randomUUID(), 1002L, driver2, "COMPLETED", fiveMinutesAgo);
        insertTrip(UUID.randomUUID(), 1003L, driver3, "COMPLETED", fiveMinutesAgo);

        redisTemplate.opsForValue().set("disp:lock:" + driver1, "test", Duration.ofMinutes(10));
        redisTemplate.opsForValue().set("disp:lock:" + driver2, "test", Duration.ofMinutes(10));
        redisTemplate.opsForValue().set("disp:lock:" + driver3, "test", Duration.ofMinutes(10));

        // Mock services
        locationService.stubFor(WireMock.post(WireMock.urlMatching("/internal/drivers/.*/free"))
            .willReturn(WireMock.aResponse().withStatus(200)));
        wsGateway.stubFor(WireMock.delete(WireMock.urlMatching("/internal/routes/.*"))
            .willReturn(WireMock.aResponse().withStatus(200)));

        // When: reconciliation runs
        reconciliationJob.reconcileBusyDrivers();

        // Then: all 3 drivers freed
        locationService.verify(3, WireMock.postRequestedFor(
            WireMock.urlMatching("/internal/drivers/.*/free")));

        assertThat(redisTemplate.hasKey("disp:lock:" + driver1)).isFalse();
        assertThat(redisTemplate.hasKey("disp:lock:" + driver2)).isFalse();
        assertThat(redisTemplate.hasKey("disp:lock:" + driver3)).isFalse();
    }

    private void insertTrip(UUID tripId, long customerId, long driverId, String status, Instant createdAt) {
        jdbcClient.sql("""
            INSERT INTO trips (id, customer_id, driver_id, status, pickup_lat, pickup_lng,
                               dropoff_lat, dropoff_lng, distance_m, fare, surge, idempotency_key,
                               version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
            .param(tripId)
            .param(customerId)
            .param(driverId)
            .param(status)
            .param(10.762622)
            .param(106.660172)
            .param(10.772622)
            .param(106.670172)
            .param(5000L)
            .param(50000L)
            .param(new BigDecimal("1.00"))
            .param("test-key-" + tripId)
            .param(0)
            .param(Timestamp.from(createdAt))
            .param(Timestamp.from(createdAt))
            .update();
    }
}
