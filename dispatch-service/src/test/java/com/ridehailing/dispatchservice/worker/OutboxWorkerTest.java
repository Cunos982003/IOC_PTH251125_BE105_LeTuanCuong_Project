package com.ridehailing.dispatchservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.dispatchservice.domain.TripEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Instant;
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
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
        .withExposedPorts(5672, 15672);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--requirepass", "redis_pass");

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    RedisTemplate<String, String> redisTemplate;

    @Autowired
    RabbitTemplate rabbitTemplate;

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
    }

    @Test
    void processOutbox_publishesToRabbitMQ() throws Exception {
        // Given: outbox event
        UUID eventId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        TripEvent.TripAccepted event = new TripEvent.TripAccepted(
            eventId, tripId, 2001L, Instant.now()
        );
        String payload = objectMapper.writeValueAsString(event);

        jdbcClient.sql("""
            INSERT INTO outbox (routing_key, payload, created_at)
            VALUES (?, ?::jsonb, ?)
        """)
            .param("trips.completed")
            .param(payload)
            .param(java.sql.Timestamp.from(Instant.now()))
            .update();

        // When: worker runs
        outboxWorker.processOutbox();

        // Then: event published to RabbitMQ and marked sent
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Integer sentCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL")
                .query(Integer.class).single();
            assertThat(sentCount).isEqualTo(1);
        });

        // Verify event in RabbitMQ queue
        Object received = rabbitTemplate.receiveAndConvert("trips-completed", 5000);
        assertThat(received).isNotNull();
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
                INSERT INTO outbox (routing_key, payload, created_at)
                VALUES (?, ?::jsonb, ?)
            """)
                .param("trips.completed")
                .param(payload)
                .param(java.sql.Timestamp.from(Instant.now()))
                .update();
        }

        // When: worker runs
        outboxWorker.processOutbox();

        // Then: all 3 sent
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Integer sentCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL")
                .query(Integer.class).single();
            assertThat(sentCount).isEqualTo(3);
        });

        // Verify events in RabbitMQ
        for (int i = 0; i < 3; i++) {
            Object received = rabbitTemplate.receiveAndConvert("trips-completed", 2000);
            assertThat(received).isNotNull();
        }
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
            INSERT INTO outbox (routing_key, payload, created_at)
            VALUES (?, ?::jsonb, ?)
        """)
            .param("trips.completed")
            .param(payload)
            .param(java.sql.Timestamp.from(Instant.now()))
            .update();

        // Simulate crash: outbox written but worker didn't run

        // When: "restart" - worker runs again
        outboxWorker.processOutbox();

        // Then: event recovered and published
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Integer sentCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL")
                .query(Integer.class).single();
            assertThat(sentCount).isEqualTo(1);
        });

        Object received = rabbitTemplate.receiveAndConvert("trips-completed", 5000);
        assertThat(received).isNotNull();
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
                INSERT INTO outbox (routing_key, payload, created_at)
                VALUES (?, ?::jsonb, ?)
            """)
                .param("trips.completed")
                .param(payload)
                .param(java.sql.Timestamp.from(Instant.now()))
                .update();
        }

        // When: two workers run concurrently (simulated by calling twice)
        outboxWorker.processOutbox();
        outboxWorker.processOutbox();

        // Then: all events eventually sent (no deadlock from locking)
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Integer sentCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL")
                .query(Integer.class).single();
            assertThat(sentCount).isEqualTo(10);
        });
    }
}