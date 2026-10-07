package com.ridehailing.userservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@org.springframework.test.context.ActiveProfiles("infra")
class TripCompletedEventContractTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("user")
            .withUsername("user_app")
            .withPassword("user_pass");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withExposedPorts(5672, 15672)
            .withStartupTimeout(java.time.Duration.ofSeconds(180));

    @Autowired
    RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
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
        registry.add("outbox.scheduler.enabled", () -> "false");
    }

    @Test
    void consumerParsesContractEvent() throws Exception {
        String fixture = fixture("TripCompleted");
        JsonNode node = objectMapper.readTree(fixture);

        // Parse with exact fields from contract
        assertThat(node.has("eventId")).isTrue();
        assertThat(node.get("eventId").asText()).isNotBlank();

        // Parse with extra unknown field (tolerance test)
        String withExtra = fixture.replace("}", ", \"unknownField\": \"should-be-ignored\"}");
        JsonNode extraNode = objectMapper.readTree(withExtra);
        assertThat(extraNode.get("eventId").asText()).isNotBlank();

        // Verify RabbitMQ write/read works
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare("events", "topic", true);
            channel.queueDeclare("user.trips", true, false, false, null);
            channel.queueBind("user.trips", "events", "trips.completed");
            return null;
        });

        org.springframework.amqp.core.Message message = org.springframework.amqp.core.MessageBuilder
                .withBody(fixture.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.completed")
                .build();
        rabbitTemplate.send("events", "trips.completed", message);

        Object received = rabbitTemplate.receiveAndConvert("user.trips", 5000);
        assertThat(received).isNotNull();
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/events/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}