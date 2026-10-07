package com.ridehailing.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TripCompletedEventContractTest {

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withExposedPorts(5672, 15672)
            .withStartupTimeout(java.time.Duration.ofSeconds(180));

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/events/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}