package com.ridehailing.dispatchservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ridehailing.dispatchservice.domain.TripEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventContractTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void completedPayloadMatchesSample() throws Exception {
        var event = new TripEvent.TripCompleted(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
                UUID.fromString("550e8400-e29b-41d4-a716-446655440002"),
                12345L, 67890L, 150000L, Instant.parse("2026-10-04T10:45:00Z"));
        assertSample("TripCompleted", event);
    }

    @Test
    void cancelledPayloadMatchesSample() throws Exception {
        var event = new TripEvent.TripCancelled(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440003"),
                UUID.fromString("550e8400-e29b-41d4-a716-446655440004"),
                12345L, 67890L, "DRIVER_NOT_FOUND", Instant.parse("2026-10-04T10:35:00Z"));
        assertSample("TripCancelled", event);
    }

    private void assertSample(String name, Object event) throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/events/" + name + ".json")) {
            assertThat(input).isNotNull();
            assertThat(mapper.readTree(mapper.writeValueAsBytes(event))).isEqualTo(mapper.readTree(input));
        }
    }
}
