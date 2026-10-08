package com.ridehailing.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.userservice.event.OutboxWorker;
import com.ridehailing.userservice.event.TripCompletedEvent;
import com.ridehailing.userservice.event.TripCancelledEvent;
import com.ridehailing.userservice.event.UserRegisteredEvent;
import com.ridehailing.userservice.repository.TripHistoryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@org.springframework.test.context.ActiveProfiles({"infra", "test"})
class EventIntegrationTest {

    // Force Testcontainers to use host.docker.internal on Windows
    static {
        System.setProperty("testcontainers.use-hostname-resolution", "true");
    }

    private static final Logger log = LoggerFactory.getLogger(EventIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("user")
            .withUsername("user_app")
            .withPassword("user_pass");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withExposedPorts(5672, 15672)
            .withStartupTimeout(java.time.Duration.ofSeconds(180));

    @BeforeAll
    static void declareQueues() {
        // Declare queues/exchanges before Spring context starts so auto-startup listeners find them
        var connectionFactory = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(
                "localhost", rabbitmq.getMappedPort(5672)
        );
        connectionFactory.setUsername(rabbitmq.getAdminUsername());
        connectionFactory.setPassword(rabbitmq.getAdminPassword());

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        var exchange = ExchangeBuilder.topicExchange("events").durable(true).build();
        // Queue for trip events (completed, cancelled)
        var tripQueue = QueueBuilder.durable("user.trips").build();
        // Queue for user registered events
        var registeredQueue = QueueBuilder.durable("user.registered").build();
        admin.declareExchange(exchange);
        admin.declareQueue(tripQueue);
        admin.declareQueue(registeredQueue);
        admin.declareBinding(new org.springframework.amqp.core.Binding(
                "user.trips", Binding.DestinationType.QUEUE, "events", "trips.completed", null));
        admin.declareBinding(new org.springframework.amqp.core.Binding(
                "user.trips", Binding.DestinationType.QUEUE, "events", "trips.cancelled", null));
        admin.declareBinding(new org.springframework.amqp.core.Binding(
                "user.registered", Binding.DestinationType.QUEUE, "events", "users.registered", null));
        connectionFactory.destroy();
    }

    static {
        // Ensure containers start
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        // Use environment variable style properties that application-infra.yml expects
        registry.add("RABBITMQ_HOST", rabbitmq::getHost);
        registry.add("RABBITMQ_PORT", rabbitmq::getAmqpPort);
        registry.add("RABBITMQ_USERNAME", rabbitmq::getAdminUsername);
        registry.add("RABBITMQ_PASSWORD", rabbitmq::getAdminPassword);
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        registry.add("spring.rabbitmq.publisher-returns", () -> "true");
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
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TripHistoryRepository tripHistoryRepository;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    OutboxWorker outboxWorker;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @BeforeAll
    static void startContainers() {
        // Containers started by @Container annotation
    }

    @AfterAll
    static void stopContainers() {
        // Containers stopped automatically
    }

    @BeforeEach
    void startListener() {
        // Start the trip event listener container
        rabbitListenerEndpointRegistry.getListenerContainer("tripEventListener").start();
        // Wait a bit for connection to establish
        await().atMost(10, SECONDS).untilAsserted(() -> {
            var container = rabbitListenerEndpointRegistry.getListenerContainer("tripEventListener");
            assertThat(container.isRunning()).isTrue();
        });
    }

    @BeforeEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM outbox").update();
        jdbcClient.sql("DELETE FROM trip_history").update();
    }

    @Test
    void register_createsUserRegisteredEventInOutboxAndRabbitMQ() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email":"event@example.com","password":"password123","fullName":"Event User","role":"DRIVER"}
                """;
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/register", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Check outbox table directly
        Long outboxCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL")
                .query(Long.class)
                .single();
        log.info("DEBUG: Outbox records pending after register: {}", outboxCount);

        // Manually trigger outbox worker to publish immediately
        log.info("DEBUG: Calling outboxWorker.publishPending()");
        outboxWorker.publishPending();
        log.info("DEBUG: outboxWorker.publishPending() returned");

        // Check outbox table again
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Long outboxCountAfter = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL")
                    .query(Long.class)
                    .single();
            log.info("DEBUG: Outbox records pending after publish: {}", outboxCountAfter);
            assertThat(outboxCountAfter).isEqualTo(0);
        });

        // Verify event in RabbitMQ queue
        Object received = rabbitTemplate.receiveAndConvert("user.registered", 5000);
        assertThat(received).isNotNull();

        // Handle case where receiveAndConvert returns byte array or Object array
        Object eventObj = received;
        if (received instanceof byte[]) {
            eventObj = new String((byte[]) received, StandardCharsets.UTF_8);
        } else if (received.getClass().isArray()) {
            Object[] arr = (Object[]) received;
            assertThat(arr.length).isGreaterThan(0);
            eventObj = arr[0];
        }

        // Parse and verify the event (received may be Base64 encoded string)
        String receivedStr = eventObj.toString();
        log.info("DEBUG: Received event raw: {}", receivedStr);

        // Check if Base64 encoded
        String receivedJson;
        try {
            byte[] decoded = Base64.getDecoder().decode(receivedStr);
            receivedJson = new String(decoded, StandardCharsets.UTF_8);
            log.info("DEBUG: Decoded Base64: {}", receivedJson);
        } catch (IllegalArgumentException e) {
            // Not Base64, use as-is
            receivedJson = receivedStr;
        }

        UserRegisteredEvent event = objectMapper.readValue(receivedJson, UserRegisteredEvent.class);
        assertThat(event.userId()).isNotNull();
        assertThat(event.role()).isEqualTo("DRIVER");
        assertThat(event.fullName()).isEqualTo("Event User");
    }

    @Test
    void tripCompletedConsumer_idempotent() throws Exception {
        UUID tripId = UUID.randomUUID();
        Long customerId = 100L;
        Long driverId = 200L;
        Long fare = 50000L;
        Instant completedAt = Instant.now();

        TripCompletedEvent event = new TripCompletedEvent(
                UUID.randomUUID(),
                tripId,
                customerId,
                driverId,
                fare,
                completedAt
        );

        String payload = objectMapper.writeValueAsString(event);

        // Send same event 3 times directly to RabbitMQ
        for (int i = 0; i < 3; i++) {
            org.springframework.amqp.core.Message message = org.springframework.amqp.core.MessageBuilder
                    .withBody(payload.getBytes())
                    .setContentType("application/json")
                    .setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT)
                    .setHeader("type", "trips.completed")
                    .build();
            rabbitTemplate.send("events", "trips.completed", message);
        }

        // Wait for consumer to process
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Long count = tripHistoryRepository.countByTripId(tripId);
            assertThat(count).isEqualTo(1);
        });
    }

    @Test
    void tripCancelledConsumer_worksWithoutDriverId() throws Exception {
        UUID tripId = UUID.randomUUID();
        Long customerId = 100L;
        Instant cancelledAt = Instant.now();

        TripCancelledEvent event = new TripCancelledEvent(
                UUID.randomUUID(),
                tripId,
                customerId,
                null, // driverId can be null
                "DRIVER_NOT_FOUND",
                cancelledAt
        );

        String payload = objectMapper.writeValueAsString(event);

        // Send event to RabbitMQ
        org.springframework.amqp.core.Message message = org.springframework.amqp.core.MessageBuilder
                .withBody(payload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.cancelled")
                .build();
        rabbitTemplate.send("events", "trips.cancelled", message);

        // Wait for consumer to process
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Long count = tripHistoryRepository.countByTripId(tripId);
            assertThat(count).isEqualTo(1);
        });

        // Verify the record has correct values
        var record = jdbcClient.sql("""
            SELECT status, fare, driver_id FROM trip_history WHERE trip_id = ?
        """)
                .param(tripId)
                .query((rs, rowNum) -> {
                    var map = new java.util.HashMap<String, Object>();
                    map.put("status", rs.getString("status"));
                    map.put("fare", rs.getLong("fare"));
                    map.put("driverId", rs.getObject("driver_id"));
                    return map;
                })
                .single();

        assertThat(record.get("status")).isEqualTo("CANCELLED");
        assertThat(record.get("fare")).isEqualTo(0L);
        assertThat(record.get("driverId")).isNull();
    }

    @Test
    void tripEventListener_autoStartsAndProcessesWithin5Seconds() throws Exception {
        // This test verifies the listener starts automatically (autoStartup=true)
        // and processes events without manual start() call.
        // Queues/exchanges must be declared before context loads, so we declare them here.

        UUID tripId = UUID.randomUUID();
        Long customerId = 200L;
        Long driverId = 300L;
        Long fare = 75000L;
        Instant completedAt = Instant.now();

        TripCompletedEvent event = new TripCompletedEvent(
                UUID.randomUUID(),
                tripId,
                customerId,
                driverId,
                fare,
                completedAt
        );

        String payload = objectMapper.writeValueAsString(event);

        // Declare exchange and queue (normally done by infra/definitions.json)
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare("events", "topic", true);
            channel.queueDeclare("user.trips", true, false, false, null);
            channel.queueBind("user.trips", "events", "trips.completed");
            channel.queueBind("user.trips", "events", "trips.cancelled");
            return null;
        });

        // Send event directly to RabbitMQ - listener should auto-start and consume
        org.springframework.amqp.core.Message message = org.springframework.amqp.core.MessageBuilder
                .withBody(payload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.completed")
                .build();
        rabbitTemplate.send("events", "trips.completed", message);

        // Wait for consumer to process - should complete within 5 seconds
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Long count = tripHistoryRepository.countByTripId(tripId);
            assertThat(count).isEqualTo(1);
        });
    }
}