package com.ridehailing.dispatchservice.repository;

import com.ridehailing.dispatchservice.domain.Trip;
import com.ridehailing.dispatchservice.domain.TripEvent;
import com.ridehailing.dispatchservice.domain.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class TripRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("dispatch")
        .withUsername("dispatch_app")
        .withPassword("dispatch_pass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--requirepass", "redis_pass");

    @Autowired
    TripRepository tripRepository;

    @Autowired
    JdbcClient jdbcClient;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("SERVER_PORT", () -> 8083);
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PASSWORD", () -> "redis_pass");
        registry.add("INTERNAL_KEY", () -> "test-internal-key");
        registry.add("JWT_SECRET", () -> "test-jwt-secret");
        registry.add("PRICING_SERVICE_URL", () -> "http://localhost:8084");
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:8085");
        registry.add("LOCATION_SERVICE_URL", () -> "http://localhost:8082");
        registry.add("WS_GATEWAY_URL", () -> "http://localhost:8001");
    }

    @BeforeEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM outbox").update();
        jdbcClient.sql("DELETE FROM offers").update();
        jdbcClient.sql("DELETE FROM trip_events").update();
        jdbcClient.sql("DELETE FROM trips").update();
    }

    @Test
    void testCreateTrip() {
        UUID tripId = UUID.randomUUID();
        Instant now = Instant.now();

        Trip trip = new Trip(
            tripId,
            1001L,
            null,
            TripStatus.CREATED,
            10.762622,
            106.660172,
            10.772622,
            106.670172,
            5000L,
            50000L,
            new BigDecimal("1.20"),
            "test-key-1",
            0,
            now,
            now
        );

        Trip created = tripRepository.create(trip);

        assertThat(created).isEqualTo(trip);

        Optional<Trip> found = tripRepository.findById(tripId);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(tripId);
        assertThat(found.get().status()).isEqualTo(TripStatus.CREATED);
    }

    @Test
    void testFindByCustomerAndIdempotencyKey() {
        UUID tripId = UUID.randomUUID();
        long customerId = 1002L;
        String idempotencyKey = "test-key-2";

        Trip trip = new Trip(
            tripId,
            customerId,
            null,
            TripStatus.CREATED,
            10.762622,
            106.660172,
            10.772622,
            106.670172,
            5000L,
            50000L,
            new BigDecimal("1.00"),
            idempotencyKey,
            0,
            Instant.now(),
            Instant.now()
        );

        tripRepository.create(trip);

        Optional<Trip> found = tripRepository.findByCustomerAndIdempotencyKey(customerId, idempotencyKey);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(tripId);
    }

    @Test
    void testTransition_Success() {
        UUID tripId = UUID.randomUUID();
        Trip trip = new Trip(
            tripId,
            1003L,
            null,
            TripStatus.CREATED,
            10.762622,
            106.660172,
            10.772622,
            106.670172,
            5000L,
            50000L,
            new BigDecimal("1.00"),
            "test-key-3",
            0,
            Instant.now(),
            Instant.now()
        );

        Trip created = tripRepository.create(trip);

        TripEvent.TripMatching event = new TripEvent.TripMatching(
            UUID.randomUUID(),
            tripId,
            Instant.now()
        );

        Trip transitioned = tripRepository.transition(created, TripStatus.MATCHING, event);

        assertThat(transitioned.status()).isEqualTo(TripStatus.MATCHING);
        assertThat(transitioned.version()).isEqualTo(1);

        // Verify trip_events recorded
        Integer eventCount = jdbcClient.sql("SELECT COUNT(*) FROM trip_events WHERE trip_id = ?")
            .param(tripId)
            .query(Integer.class)
            .single();
        assertThat(eventCount).isEqualTo(1);

        // Verify outbox
        Integer outboxCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox")
            .query(Integer.class)
            .single();
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void testTransition_InvalidTransition() {
        UUID tripId = UUID.randomUUID();
        Trip trip = new Trip(
            tripId,
            1004L,
            null,
            TripStatus.COMPLETED,
            10.762622,
            106.660172,
            10.772622,
            106.670172,
            5000L,
            50000L,
            new BigDecimal("1.00"),
            "test-key-4",
            0,
            Instant.now(),
            Instant.now()
        );

        Trip created = tripRepository.create(trip);

        TripEvent.TripMatching event = new TripEvent.TripMatching(
            UUID.randomUUID(),
            tripId,
            Instant.now()
        );

        assertThatThrownBy(() ->
            tripRepository.transition(created, TripStatus.MATCHING, event)
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Cannot transition from COMPLETED to MATCHING");
    }

    @Test
    void testTransition_OptimisticLocking() throws Exception {
        UUID tripId = UUID.randomUUID();
        Trip trip = new Trip(
            tripId,
            1005L,
            null,
            TripStatus.CREATED,
            10.762622,
            106.660172,
            10.772622,
            106.670172,
            5000L,
            50000L,
            new BigDecimal("1.00"),
            "test-key-5",
            0,
            Instant.now(),
            Instant.now()
        );

        Trip created = tripRepository.create(trip);

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Runnable task = () -> {
            try {
                TripEvent.TripMatching event = new TripEvent.TripMatching(
                    UUID.randomUUID(),
                    tripId,
                    Instant.now()
                );
                tripRepository.transition(created, TripStatus.MATCHING, event);
                successCount.incrementAndGet();
            } catch (OptimisticLockingFailureException e) {
                failCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        };

        executor.submit(task);
        executor.submit(task);

        latch.await();
        executor.shutdown();

        // Only one should succeed due to optimistic locking
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);

        // Verify final state
        Optional<Trip> finalTrip = tripRepository.findById(tripId);
        assertThat(finalTrip).isPresent();
        assertThat(finalTrip.get().status()).isEqualTo(TripStatus.MATCHING);
        assertThat(finalTrip.get().version()).isEqualTo(1);
    }

    @Test
    void testAssignDriver() {
        UUID tripId = UUID.randomUUID();
        Trip trip = new Trip(
            tripId,
            1006L,
            null,
            TripStatus.MATCHING,
            10.762622,
            106.660172,
            10.772622,
            106.670172,
            5000L,
            50000L,
            new BigDecimal("1.00"),
            "test-key-6",
            0,
            Instant.now(),
            Instant.now()
        );

        Trip created = tripRepository.create(trip);

        long driverId = 2001L;
        Trip assigned = tripRepository.assignDriver(created, driverId);

        assertThat(assigned.driverId()).isEqualTo(driverId);
        assertThat(assigned.version()).isEqualTo(1);

        Optional<Trip> found = tripRepository.findById(tripId);
        assertThat(found).isPresent();
        assertThat(found.get().driverId()).isEqualTo(driverId);
    }

    @Test
    void testUniqueDriverActiveTrip() {
        long driverId = 2002L;

        // Create first active trip
        UUID tripId1 = UUID.randomUUID();
        Trip trip1 = new Trip(
            tripId1,
            1007L,
            driverId,
            TripStatus.ACCEPTED,
            10.762622,
            106.660172,
            10.772622,
            106.670172,
            5000L,
            50000L,
            new BigDecimal("1.00"),
            "test-key-7",
            0,
            Instant.now(),
            Instant.now()
        );

        tripRepository.create(trip1);

        // Try to create second active trip with same driver - should fail
        // Note: Using different customer to avoid idempotency key constraint
        UUID tripId2 = UUID.randomUUID();
        Trip trip2 = new Trip(
            tripId2,
            1008L,
            driverId,
            TripStatus.PICKING_UP,
            10.762622,
            106.660172,
            10.772622,
            106.670172,
            5000L,
            50000L,
            new BigDecimal("1.00"),
            "test-key-8",
            0,
            Instant.now(),
            Instant.now()
        );

        assertThatThrownBy(() -> tripRepository.create(trip2))
            .isInstanceOf(Exception.class)
            .satisfies(e -> {
                String msg = e.getMessage();
                assertThat(msg).containsAnyOf("uq_driver_active_trip", "duplicate key", "unique constraint");
            });

        // Creating a non-active trip should work
        UUID tripId3 = UUID.randomUUID();
        Trip trip3 = new Trip(
            tripId3,
            1009L,
            driverId,
            TripStatus.COMPLETED,
            10.762622,
            106.660172,
            10.772622,
            106.670172,
            5000L,
            50000L,
            new BigDecimal("1.00"),
            "test-key-9",
            0,
            Instant.now(),
            Instant.now()
        );

        Trip created = tripRepository.create(trip3);
        assertThat(created).isNotNull();
    }
}
