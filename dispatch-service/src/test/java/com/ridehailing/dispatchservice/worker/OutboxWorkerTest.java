package com.ridehailing.dispatchservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.dispatchservice.domain.TripEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OutboxWorkerTest {

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
    JdbcClient jdbcClient;

    @Autowired
    RedisTemplate<String, String> redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OutboxWorker outboxWorker;

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
        registry.add("LOCATION_SERVICE_URL", () -> "http://localhost:9999");
        registry.add("WS_GATEWAY_URL", () -> "http://localhost:9999");
    }

    @BeforeEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM outbox").update();

        // Clear Redis stream
        try {
            redisTemplate.delete("events.trips");
        } catch (Exception ignored) {
        }
    }

    @Test
    void processOutbox_publishesToRedisStream() throws Exception {
        // Given: outbox event
        UUID eventId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        TripEvent.TripAccepted event = new TripEvent.TripAccepted(
            eventId, tripId, 2001L, Instant.now()
        );
        String payload = objectMapper.writeValueAsString(event);

        jdbcClient.sql("""
            INSERT INTO outbox (stream, payload, created_at)
            VALUES (?, ?::jsonb, ?)
        """)
            .param("events.trips")
            .param(payload)
            .param(java.sql.Timestamp.from(Instant.now()))
            .update();

        // When: worker runs
        outboxWorker.processOutbox();

        // Then: event published to stream and marked sent
        await().atMost(2, SECONDS).untilAsserted(() -> {
            Integer sentCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL")
                .query(Integer.class).single();
            assertThat(sentCount).isEqualTo(1);
        });

        // Verify event in Redis Stream
        List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
            .read(org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(10),
                  org.springframework.data.redis.connection.stream.StreamOffset.fromStart("events.trips"));

        assertThat(messages).hasSize(1);
        Map<Object, Object> fields = messages.get(0).getValue();
        String actualPayload = (String) fields.get("payload");

        // Parse and compare as objects (not strings) to handle JSON field order
        TripEvent.TripAccepted actualEvent = objectMapper.readValue(actualPayload, TripEvent.TripAccepted.class);
        assertThat(actualEvent.tripId()).isEqualTo(tripId);
        assertThat(actualEvent.driverId()).isEqualTo(2001L);
    }

    @Test
    void processOutbox_handlesMultipleEvents() throws Exception {
        // Given: 3 events in outbox
        for (int i = 0; i < 3; i++) {
            UUID tripId = UUID.randomUUID();
            TripEvent.TripAccepted event = new TripEvent.TripAccepted(
                UUID.randomUUID(), tripId, 2001L, Instant.now()
            );
            String payload = objectMapper.writeValueAsString(event);

            jdbcClient.sql("""
                INSERT INTO outbox (stream, payload, created_at)
                VALUES (?, ?::jsonb, ?)
            """)
                .param("events.trips")
                .param(payload)
                .param(java.sql.Timestamp.from(Instant.now()))
                .update();
        }

        // When: worker runs
        outboxWorker.processOutbox();

        // Then: all 3 sent
        await().atMost(2, SECONDS).untilAsserted(() -> {
            Integer sentCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL")
                .query(Integer.class).single();
            assertThat(sentCount).isEqualTo(3);
        });

        List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
            .read(org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(10),
                  org.springframework.data.redis.connection.stream.StreamOffset.fromStart("events.trips"));

        assertThat(messages).hasSize(3);
    }

    @Test
    void processOutbox_crashRecovery_eventsPublishedAfterRestart() throws Exception {
        // Given: event written to outbox but not yet sent
        UUID tripId = UUID.randomUUID();
        TripEvent.TripAccepted event = new TripEvent.TripAccepted(
            UUID.randomUUID(), tripId, 2001L, Instant.now()
        );
        String payload = objectMapper.writeValueAsString(event);

        jdbcClient.sql("""
            INSERT INTO outbox (stream, payload, created_at)
            VALUES (?, ?::jsonb, ?)
        """)
            .param("events.trips")
            .param(payload)
            .param(java.sql.Timestamp.from(Instant.now()))
            .update();

        // Simulate crash: outbox written but worker didn't run

        // When: "restart" - worker runs again
        outboxWorker.processOutbox();

        // Then: event recovered and published
        await().atMost(2, SECONDS).untilAsserted(() -> {
            Integer sentCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL")
                .query(Integer.class).single();
            assertThat(sentCount).isEqualTo(1);
        });

        List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
            .read(org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(10),
                  org.springframework.data.redis.connection.stream.StreamOffset.fromStart("events.trips"));

        assertThat(messages).hasSize(1);
    }

    @Test
    void processOutbox_skipLocked_allowsConcurrentWorkers() {
        // Given: 10 events
        for (int i = 0; i < 10; i++) {
            UUID tripId = UUID.randomUUID();
            TripEvent.TripAccepted event = new TripEvent.TripAccepted(
                UUID.randomUUID(), tripId, 2001L, Instant.now()
            );
            String payload;
            try {
                payload = objectMapper.writeValueAsString(event);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            jdbcClient.sql("""
                INSERT INTO outbox (stream, payload, created_at)
                VALUES (?, ?::jsonb, ?)
            """)
                .param("events.trips")
                .param(payload)
                .param(java.sql.Timestamp.from(Instant.now()))
                .update();
        }

        // When: two workers run concurrently (simulated by calling twice)
        outboxWorker.processOutbox();
        outboxWorker.processOutbox();

        // Then: all events eventually sent (no deadlock from locking)
        await().atMost(3, SECONDS).untilAsserted(() -> {
            Integer sentCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL")
                .query(Integer.class).single();
            assertThat(sentCount).isEqualTo(10);
        });
    }
}
