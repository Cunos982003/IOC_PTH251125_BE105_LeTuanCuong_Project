package com.ridehailing.dispatchservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.ridehailing.dispatchservice.domain.TripStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("dispatch")
        .withUsername("dispatch_app")
        .withPassword("dispatch_pass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--requirepass", "redis_pass");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
        .withExposedPorts(5672, 15672);

    static WireMockServer locationService;
    static WireMockServer wsGateway;
    static WireMockServer pricingService;
    static WireMockServer paymentService;

    static {
        locationService = new WireMockServer(0);
        locationService.start();

        wsGateway = new WireMockServer(0);
        wsGateway.start();

        pricingService = new WireMockServer(0);
        pricingService.start();

        paymentService = new WireMockServer(0);
        paymentService.start();
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    RedisTemplate<String, String> redisTemplate;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    com.ridehailing.dispatchservice.worker.StuckTripScanner stuckTripScanner;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("SERVER_PORT", () -> 0);
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "redis_pass");
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        registry.add("spring.rabbitmq.publisher-returns", () -> "true");
        registry.add("INTERNAL_KEY", () -> "test-internal-key");
        registry.add("JWT_SECRET", () -> "test-jwt-secret");
        registry.add("PRICING_SERVICE_URL", () -> "http://localhost:" + pricingService.port());
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:" + paymentService.port());
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
        if (pricingService != null) pricingService.stop();
        if (paymentService != null) paymentService.stop();
    }

    @BeforeEach
    void setup() {
        // Declare exchange and queue for driver offers
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare("events", "topic", true);
            channel.queueDeclare("trips-offered", true, false, false, null);
            channel.queueBind("trips-offered", "events", "trips.offered");
            return null;
        });
    }

    @BeforeEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM outbox").update();
        jdbcClient.sql("DELETE FROM offers").update();
        jdbcClient.sql("DELETE FROM trip_events").update();
        jdbcClient.sql("DELETE FROM trips").update();

        // Clear Redis locks
        Set<String> keys = redisTemplate.keys("disp:lock:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        locationService.resetAll();
        wsGateway.resetAll();
        pricingService.resetAll();
        paymentService.resetAll();

        // Setup common stubs that all tests need
        locationService.stubFor(WireMock.post(WireMock.urlMatching("/internal/drivers/.*/busy"))
            .willReturn(WireMock.aResponse().withStatus(200).withFixedDelay(50)));
        locationService.stubFor(WireMock.post(WireMock.urlMatching("/internal/drivers/.*/free"))
            .willReturn(WireMock.aResponse().withStatus(200).withFixedDelay(50)));
        wsGateway.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/push"))
            .willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"delivered\": true}")
                .withFixedDelay(50)));
        wsGateway.stubFor(WireMock.delete(WireMock.urlMatching("/internal/routes/.*"))
            .willReturn(WireMock.aResponse().withStatus(200).withFixedDelay(50)));
    }

    @Test
    void scenario1_50CustomersOneDriver_OnlyOneAccepted() throws Exception {
        // Setup: 1 driver, 50 customers creating trips simultaneously
        long driverId = 2001L;
        int customerCount = 50;

        // Mock services
        pricingService.stubFor(WireMock.post(WireMock.urlEqualTo("/internal/quote"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(50)
                .withBody("{\"distanceM\":5000,\"fare\":50000,\"surge\":1.0}")));

        paymentService.stubFor(WireMock.get(WireMock.urlMatching("/internal/wallets/.*/balance"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(50)
                .withBody("{\"balance\":1000000}")));

        locationService.stubFor(WireMock.get(WireMock.urlMatching("/internal/drivers/nearby.*"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(50)
                .withBody("""
                    [
                        {"driverId": %d, "lat": 10.763, "lng": 106.661, "distanceM": 500}
                    ]
                """.formatted(driverId))));

        // Create 50 trips concurrently
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(customerCount);
        List<UUID> tripIds = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < customerCount; i++) {
            long customerId = 1000L + i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    UUID tripId = createTrip(customerId);
                    tripIds.add(tripId);
                } catch (Exception e) {
                    System.err.println("Failed to create trip: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all at once
        startLatch.countDown();
        boolean allDone = doneLatch.await(30, TimeUnit.SECONDS);
        assertThat(allDone).isTrue();

        // Wait for matching loops to start and make offers (longer timeout for 50 trips)
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            List<Map<String, Object>> offers = jdbcClient.sql("""
                SELECT trip_id, driver_id FROM offers
                WHERE driver_id = ? AND status = 'OFFERED'
                ORDER BY offered_at LIMIT 1
            """)
                .param(driverId)
                .query((rs, rowNum) -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("tripId", rs.getString("trip_id"));
                    map.put("driverId", rs.getLong("driver_id"));
                    return map;
                })
                .list();
            return !offers.isEmpty();
        });

        List<Map<String, Object>> firstOffer = jdbcClient.sql("""
            SELECT trip_id, driver_id FROM offers
            WHERE driver_id = ? AND status = 'OFFERED'
            ORDER BY offered_at LIMIT 1
        """)
            .param(driverId)
            .query((rs, rowNum) -> {
                Map<String, Object> map = new HashMap<>();
                map.put("tripId", rs.getString("trip_id"));
                map.put("driverId", rs.getLong("driver_id"));
                return map;
            })
            .list();

        if (!firstOffer.isEmpty()) {
            UUID acceptedTripId = UUID.fromString((String) firstOffer.get(0).get("tripId"));

            webTestClient.post()
                .uri("/internal/trips/{tripId}/accept", acceptedTripId)
                .header("X-Internal-Key", "test-internal-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("driverId", driverId))
                .exchange()
                .expectStatus().isOk();
        }

        // Manually trigger stuck trip scanner since scheduling is disabled in tests
        // First, backdate MATCHING trips so scanner will transition them
        jdbcClient.sql("""
            UPDATE trips
            SET created_at = created_at - INTERVAL '3 minutes'
            WHERE status = 'MATCHING'
        """).update();

        stuckTripScanner.scanStuckTrips();

        // Wait for all trips to settle
        await().atMost(25, TimeUnit.SECONDS).until(() -> {
            Integer matchingCount = jdbcClient.sql("SELECT COUNT(*) FROM trips WHERE status = 'MATCHING'")
                .query(Integer.class).single();
            return matchingCount == 0;
        });

        // Verify: exactly 1 trip ACCEPTED with this driver
        Integer acceptedCount = jdbcClient.sql("""
            SELECT COUNT(*) FROM trips
            WHERE driver_id = ? AND status IN ('ACCEPTED', 'PICKING_UP', 'IN_TRIP')
        """)
            .param(driverId)
            .query(Integer.class)
            .single();

        assertThat(acceptedCount).isEqualTo(1);

        // Verify: all other trips are terminal (NO_DRIVER_FOUND or CANCELLED)
        Integer terminalCount = jdbcClient.sql("""
            SELECT COUNT(*) FROM trips
            WHERE status IN ('NO_DRIVER_FOUND', 'CANCELLED')
        """)
            .query(Integer.class)
            .single();

        assertThat(acceptedCount + terminalCount).isEqualTo(customerCount);

        executor.shutdown();
    }

    @Test
    void scenario2_LockDeletedMidway_UniqueIndexStillProtects() throws Exception {
        // Setup: create trip in MATCHING with driver assigned
        UUID tripId = UUID.randomUUID();
        long customerId = 1001L;
        long driverId = 2001L;

        insertTripMatching(tripId, customerId);
        insertOffer(tripId, driverId, "OFFERED");

        // Set lock
        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripId.toString(), Duration.ofSeconds(20));

        // Accept trip successfully
        webTestClient.post()
            .uri("/internal/trips/{tripId}/accept", tripId)
            .header("X-Internal-Key", "test-internal-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("driverId", driverId))
            .exchange()
            .expectStatus().isOk();

        // Delete lock (simulate Redis failure)
        redisTemplate.delete("disp:lock:" + driverId);

        // Try to create second trip and accept with same driver
        UUID tripId2 = UUID.randomUUID();
        long customerId2 = 1002L;

        insertTripMatching(tripId2, customerId2);
        insertOffer(tripId2, driverId, "OFFERED");
        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripId2.toString(), Duration.ofSeconds(20));

        // Try to accept (should fail due to unique index)
        webTestClient.post()
            .uri("/internal/trips/{tripId}/accept", tripId2)
            .header("X-Internal-Key", "test-internal-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("driverId", driverId))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("DRIVER_ALREADY_BUSY");

        // Verify first trip still ACCEPTED
        String status1 = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
            .param(tripId)
            .query(String.class)
            .single();
        assertThat(status1).isEqualTo("ACCEPTED");

        // Verify second trip still MATCHING
        String status2 = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
            .param(tripId2)
            .query(String.class)
            .single();
        assertThat(status2).isEqualTo("MATCHING");
    }

    @Test
    void scenario3_CustomerCancelAndDriverAcceptSimultaneously_ConsistentState() throws Exception {
        UUID tripId = UUID.randomUUID();
        long customerId = 1003L;
        long driverId = 2003L;

        insertTripMatching(tripId, customerId);
        insertOffer(tripId, driverId, "OFFERED");
        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripId.toString(), Duration.ofSeconds(20));

        // Execute cancel and accept simultaneously
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                webTestClient.post()
                    .uri("/api/v1/rides/{tripId}/cancel", tripId)
                    .header("X-User-Id", String.valueOf(customerId))
                    .exchange()
                    .expectStatus().is2xxSuccessful();
                successCount.incrementAndGet();
            } catch (Exception e) {
                System.err.println("Cancel failed: " + e.getMessage());
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                webTestClient.post()
                    .uri("/internal/trips/{tripId}/accept", tripId)
                    .header("X-Internal-Key", "test-internal-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("driverId", driverId))
                    .exchange()
                    .expectStatus().is2xxSuccessful();
                successCount.incrementAndGet();
            } catch (Exception e) {
                System.err.println("Accept failed: " + e.getMessage());
            }
        });

        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Verify: exactly one operation succeeded
        assertThat(successCount.get()).isEqualTo(1);

        // Verify: trip is in consistent state
        Map<String, Object> trip = jdbcClient.sql("""
            SELECT status, driver_id FROM trips WHERE id = ?
        """)
            .param(tripId)
            .query((rs, rowNum) -> {
                Map<String, Object> map = new HashMap<>();
                map.put("status", rs.getString("status"));
                map.put("driverId", rs.getObject("driver_id"));
                return map;
            })
            .single();

        String status = (String) trip.get("status");
        Object driverIdValue = trip.get("driverId");

        // Must be either CANCELLED (no driver) or ACCEPTED (with driver)
        if ("CANCELLED".equals(status)) {
            // If cancelled, should not have driver in active state
            assertThat(driverIdValue).isNull();
        } else if ("ACCEPTED".equals(status)) {
            // If accepted, must have driver
            assertThat(driverIdValue).isNotNull();
            assertThat(((Number) driverIdValue).longValue()).isEqualTo(driverId);
        } else {
            throw new AssertionError("Unexpected status: " + status);
        }
    }

    @Test
    void scenario4_DriverAlreadyOnTripA_CannotAcceptTripB() throws Exception {
        UUID tripA = UUID.randomUUID();
        UUID tripB = UUID.randomUUID();
        long customerA = 1004L;
        long customerB = 1005L;
        long driverId = 2004L;

        // Trip A: driver already assigned and ACCEPTED
        insertTripAccepted(tripA, customerA, driverId);

        // Trip B: new trip, driver gets offered
        insertTripMatching(tripB, customerB);
        insertOffer(tripB, driverId, "OFFERED");
        redisTemplate.opsForValue().set("disp:lock:" + driverId, tripB.toString(), Duration.ofSeconds(20));

        // Try to accept trip B (should fail)
        webTestClient.post()
            .uri("/internal/trips/{tripId}/accept", tripB)
            .header("X-Internal-Key", "test-internal-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("driverId", driverId))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("DRIVER_ALREADY_BUSY");

        // Verify trip A still ACCEPTED
        String statusA = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
            .param(tripA)
            .query(String.class)
            .single();
        assertThat(statusA).isEqualTo("ACCEPTED");

        // Verify trip B still MATCHING
        String statusB = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
            .param(tripB)
            .query(String.class)
            .single();
        assertThat(statusB).isEqualTo("MATCHING");
    }

    private UUID createTrip(long customerId) {
        String response = webTestClient.post()
            .uri("/api/v1/trips")
            .header("X-User-Id", String.valueOf(customerId))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "pickupLat", 10.762622,
                "pickupLng", 106.660172,
                "dropoffLat", 10.772622,
                "dropoffLng", 106.670172,
                "idempotencyKey", "test-" + customerId + "-" + System.nanoTime()
            ))
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

        // Parse tripId from response
        try {
            Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
            return UUID.fromString((String) parsed.get("tripId"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse trip response", e);
        }
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
