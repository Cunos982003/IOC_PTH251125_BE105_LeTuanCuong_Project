package com.ridehailing.userservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.userservice.event.UserRegisteredEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserRegisteredEventProducerContractTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void producerSerializesContractEvent() throws Exception {
        String expectedFixture = fixture("UserRegistered");
        JsonNode expected = objectMapper.readTree(expectedFixture);

        // Create event with same values as contract
        var event = new UserRegisteredEvent(
            UUID.fromString(expected.get("eventId").asText()),
            expected.get("userId").asLong(),
            expected.get("role").asText(),
            expected.get("fullName").asText(),
            Instant.parse(expected.get("registeredAt").asText())
        );

        String actual = objectMapper.writeValueAsString(event);
        JsonNode actualNode = objectMapper.readTree(actual);

        // Verify structure matches
        assertThat(actualNode.has("eventId")).isTrue();
        assertThat(actualNode.has("userId")).isTrue();
        assertThat(actualNode.has("role")).isTrue();
        assertThat(actualNode.has("fullName")).isTrue();
        assertThat(actualNode.has("registeredAt")).isTrue();

        // Verify values match
        assertThat(actualNode.get("eventId").asText()).isEqualTo(expected.get("eventId").asText());
        assertThat(actualNode.get("userId").asLong()).isEqualTo(expected.get("userId").asLong());
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/events/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}