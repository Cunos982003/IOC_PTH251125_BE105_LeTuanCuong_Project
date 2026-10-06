package com.ridehailing.dispatchservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ridehailing.dispatchservice.domain.TripEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TripCompletedEventProducerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void producerSerializesContractEvent() throws Exception {
        String expectedFixture = fixture("TripCompleted");
        JsonNode expected = objectMapper.readTree(expectedFixture);

        // Create event with same values as contract
        var event = new TripEvent.TripCompleted(
            UUID.fromString(expected.get("eventId").asText()),
            UUID.fromString(expected.get("tripId").asText()),
            expected.get("customerId").asLong(),
            expected.get("driverId").asLong(),
            expected.get("fare").asLong(),
            Instant.parse(expected.get("completedAt").asText())
        );

        String actual = objectMapper.writeValueAsString(event);
        JsonNode actualNode = objectMapper.readTree(actual);

        // Verify structure matches
        assertThat(actualNode.has("eventId")).isTrue();
        assertThat(actualNode.has("tripId")).isTrue();
        assertThat(actualNode.has("customerId")).isTrue();
        assertThat(actualNode.has("driverId")).isTrue();
        assertThat(actualNode.has("fare")).isTrue();
        assertThat(actualNode.has("completedAt")).isTrue();

        // Verify values match
        assertThat(actualNode.get("eventId").asText()).isEqualTo(expected.get("eventId").asText());
        assertThat(actualNode.get("tripId").asText()).isEqualTo(expected.get("tripId").asText());
        assertThat(actualNode.get("customerId").asLong()).isEqualTo(expected.get("customerId").asLong());
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/events/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}