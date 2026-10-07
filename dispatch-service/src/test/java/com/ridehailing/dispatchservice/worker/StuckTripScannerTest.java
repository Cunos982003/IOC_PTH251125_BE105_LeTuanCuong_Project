package com.ridehailing.dispatchservice.worker;

import com.ridehailing.dispatchservice.domain.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class StuckTripScannerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("dispatch")
        .withUsername("dispatch_app")
        .withPassword("dispatch_pass");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
        .withExposedPorts(5672, 15672);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--requirepass", "redis_pass");

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    StuckTripScanner scanner;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("SERVER_PORT", () -> 0);
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        registry.add("spring.rabbitmq.publisher-returns", () -> "true");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "redis_pass");
        registry.add("INTERNAL_KEY", () -> "test-internal-key");
        registry.add("JWT_SECRET", () -> "test-jwt-secret");
        registry.add("PRICING_SERVICE_URL", () -> "http://localhost:9999");
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:9999");
        registry.add("LOCATION_SERVICE_URL", () -> "http://localhost:9999");
        registry.add("WS_GATEWAY_URL", () -> "http://localhost:9999");
    }

    @BeforeEach
    void setup() {
        // Declare exchange and queues for testing
        TopicExchange exchange = new TopicExchange("events", true, false);
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare(exchange.getName(), "topic", true);
            return null;
        });

        // Declare queues and bindings for trips.completed and trips.cancelled
        String[] routingKeys = {"trips.completed", "trips.cancelled"};
        for (String rk : routingKeys) {
            String queueName = rk.replace(".", "-");
            rabbitTemplate.execute(channel -> {
                channel.queueDeclare(queueName, true, false, false, null);
                channel.queueBind(queueName, "events", rk);
                return null;
            });
        }
    }

    @BeforeEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM outbox").update();
        jdbcClient.sql("DELETE FROM offers").update();
        jdbcClient.sql("DELETE FROM trip_events").update();
        jdbcClient.sql("DELETE FROM trips").update();
    }

    @Test
    void scanStuckTrips_transitionsOldMatchingToNoDriverFound() {
        // Given: MATCHING trip older than 2 minutes
        UUID oldTrip = UUID.randomUUID();
        Instant threeMinutesAgo = Instant.now().minus(3, ChronoUnit.MINUTES);
        insertTrip(oldTrip, 1001L, null, "MATCHING", threeMinutesAgo);

        // And: recent MATCHING trip (should not be touched)
        UUID recentTrip = UUID.randomUUID();
        insertTrip(recentTrip, 1002L, null, "MATCHING", Instant.now());

        // When: scanner runs
        scanner.scanStuckTrips();

        // Then: old trip transitioned to NO_DRIVER_FOUND
        String oldStatus = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
            .param(oldTrip)
            .query(String.class)
            .single();
        assertThat(oldStatus).isEqualTo("NO_DRIVER_FOUND");

        // And: recent trip still MATCHING
        String recentStatus = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
            .param(recentTrip)
            .query(String.class)
            .single();
        assertThat(recentStatus).isEqualTo("MATCHING");

        // And: event written to outbox (NO_DRIVER_FOUND event uses trips.completed routing)
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Integer outboxCount = jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox
                WHERE routing_key = 'trips.completed'
            """)
                .query(Integer.class)
                .single();
            assertThat(outboxCount).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void scanStuckTrips_doesNotTouchActiveTrips() {
        // Given: ACCEPTED trip older than 30 minutes
        UUID acceptedTrip = UUID.randomUUID();
        Instant fortyMinutesAgo = Instant.now().minus(40, ChronoUnit.MINUTES);
        insertTrip(acceptedTrip, 1001L, 2001L, "ACCEPTED", fortyMinutesAgo);

        // When: scanner runs
        scanner.scanStuckTrips();

        // Then: trip still ACCEPTED (only logged warning)
        String status = jdbcClient.sql("SELECT status FROM trips WHERE id = ?")
            .param(acceptedTrip)
            .query(String.class)
            .single();
        assertThat(status).isEqualTo("ACCEPTED");
    }

    @Test
    void scanStuckTrips_handlesMultipleStuckTrips() {
        // Given: 3 stuck MATCHING trips
        UUID trip1 = UUID.randomUUID();
        UUID trip2 = UUID.randomUUID();
        UUID trip3 = UUID.randomUUID();
        Instant threeMinutesAgo = Instant.now().minus(3, ChronoUnit.MINUTES);

        insertTrip(trip1, 1001L, null, "MATCHING", threeMinutesAgo);
        insertTrip(trip2, 1002L, null, "MATCHING", threeMinutesAgo);
        insertTrip(trip3, 1003L, null, "MATCHING", threeMinutesAgo);

        // When: scanner runs
        scanner.scanStuckTrips();

        // Then: all 3 transitioned
        Integer noDriverCount = jdbcClient.sql("""
            SELECT COUNT(*) FROM trips
            WHERE status = 'NO_DRIVER_FOUND'
            AND id IN (?, ?, ?)
        """)
            .param(trip1)
            .param(trip2)
            .param(trip3)
            .query(Integer.class)
            .single();

        assertThat(noDriverCount).isEqualTo(3);
    }

    @Test
    void scanStuckTrips_idempotent_doesNotRetransitionNoDriverFound() {
        // Given: trip already transitioned to NO_DRIVER_FOUND
        UUID tripId = UUID.randomUUID();
        Instant threeMinutesAgo = Instant.now().minus(3, ChronoUnit.MINUTES);
        insertTrip(tripId, 1001L, null, "NO_DRIVER_FOUND", threeMinutesAgo);

        Integer outboxCountBefore = jdbcClient.sql("""
            SELECT COUNT(*) FROM outbox
            WHERE routing_key = 'trips.completed'
        """)
            .query(Integer.class)
            .single();

        // When: scanner runs again
        scanner.scanStuckTrips();

        // Then: no additional events created
        Integer outboxCountAfter = jdbcClient.sql("""
            SELECT COUNT(*) FROM outbox
            WHERE routing_key = 'trips.completed'
        """)
            .query(Integer.class)
            .single();

        assertThat(outboxCountAfter).isEqualTo(outboxCountBefore);
    }

    private void insertTrip(UUID tripId, long customerId, Long driverId, String status, Instant createdAt) {
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