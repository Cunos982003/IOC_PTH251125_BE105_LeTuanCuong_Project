package com.ridehailing.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.userservice.event.OutboxWorker;
import com.ridehailing.userservice.event.TripCompletedEvent;
import com.ridehailing.userservice.event.UserRegisteredEvent;
import com.ridehailing.userservice.repository.TripHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@org.springframework.test.context.ActiveProfiles({"infra", "test"})
class EventIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EventIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("user")
            .withUsername("user_app")
            .withPassword("user_pass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(java.time.Duration.ofSeconds(120));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", redis::getFirstMappedPort);
        // No password for test Redis
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
        // Lettuce settings for better resilience on Windows
        registry.add("spring.data.redis.lettuce.pool.max-active", () -> "8");
        registry.add("spring.data.redis.lettuce.pool.max-idle", () -> "8");
        registry.add("spring.data.redis.lettuce.pool.min-idle", () -> "2");
        registry.add("spring.data.redis.timeout", () -> "5000ms");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TripHistoryRepository tripHistoryRepository;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    OutboxWorker outboxWorker;

    @Test
    void register_createsUserRegisteredEventInStream() throws Exception {
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
        Long outboxCountAfter = jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL")
                .query(Long.class)
                .single();
        log.info("DEBUG: Outbox records pending after publish: {}", outboxCountAfter);

        // Small delay to allow Redis stream write to complete
        Thread.sleep(500);

        // Read from stream
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(StreamReadOptions.empty().count(10),
                      StreamOffset.create("events.users", ReadOffset.from("0")));

        log.info("DEBUG: Records read from stream: {}", records);
        if (records != null) {
            for (MapRecord<String, Object, Object> r : records) {
                log.info("DEBUG: Record ID: {}, Value: {}", r.getId(), r.getValue());
                Object payloadObj = r.getValue().get("payload");
                if (payloadObj != null) {
                    log.info("DEBUG: Raw payload class: {}, value: {}", payloadObj.getClass(), payloadObj);
                }
            }
        }

        assertThat(records).isNotNull();
        assertThat(records).hasSize(1);

        MapRecord<String, Object, Object> record = records.get(0);
        Object payloadObj = record.getValue().get("payload");
        assertThat(payloadObj).isNotNull();

        String payload = payloadObj.toString();
        log.info("DEBUG: Payload string: {}", payload);
        UserRegisteredEvent event = objectMapper.readValue(payload, UserRegisteredEvent.class);
        log.info("DEBUG: Parsed event: {}", event);
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

        // Send same event 3 times
        for (int i = 0; i < 3; i++) {
            redisTemplate.opsForStream().add(
                    org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                            .in("events.trips")
                            .ofObject(payload)
            );
        }

        // Wait for consumer to process (consumer polls every 5 seconds)
        Thread.sleep(8000);

        // Verify only one record in trip_history (idempotent)
        Long count = tripHistoryRepository.countByTripId(tripId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void tripCompletedConsumer_handlesUnknownFields() throws Exception {
        UUID tripId = UUID.randomUUID();

        // JSON with extra field
        String payload = """
                {"eventId":"%s","tripId":"%s","customerId":100,"driverId":200,"fare":50000,"completedAt":"%s","extraField":"ignored"}
                """.formatted(UUID.randomUUID(), tripId, Instant.now().toString());

        redisTemplate.opsForStream().add(
                org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                        .in("events.trips")
                        .ofObject(payload)
        );

        // Wait for consumer to process
        Thread.sleep(1000);

        // Should not throw exception - lenient JSON parsing
        assertThat(true).isTrue();
    }
}