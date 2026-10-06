package com.ridehailing.paymentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ridehailing.paymentservice.event.TripEvent;
import com.ridehailing.paymentservice.event.UserRegisteredEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventContractTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private ObjectNode sample(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/events/" + name + ".json")) {
            assertThat(input).isNotNull();
            return (ObjectNode) mapper.readTree(input);
        }
    }

    @Test
    void completedParsesSampleAndUnknownFields() throws Exception {
        var json = sample("TripCompleted");
        for (int i = 0; i < 2; i++) {
            var event = mapper.treeToValue(json, TripEvent.class);
            assertThat(event.completed()).isTrue();
            assertThat(event.tripId().toString()).isEqualTo("550e8400-e29b-41d4-a716-446655440002");
            assertThat(event.customerId()).isEqualTo(12345L);
            assertThat(event.driverId()).isEqualTo(67890L);
            assertThat(event.fare()).isEqualTo(150000L);
            assertThat(event.completedAt()).isEqualTo(java.time.Instant.parse("2026-10-04T10:45:00Z"));
            json.put("futureField", "ignored");
        }
    }

    @Test
    void cancelledParsesUnknownAndOmittedOptionalDriver() throws Exception {
        var json = sample("TripCancelled");
        var event = mapper.treeToValue(json, TripEvent.class);
        assertThat(event.cancelled()).isTrue();
        assertThat(event.completed()).isFalse();
        assertThat(event.driverId()).isEqualTo(67890L);
        assertThat(event.customerId()).isEqualTo(12345L);
        assertThat(event.reason()).isEqualTo("DRIVER_NOT_FOUND");
        assertThat(event.cancelledAt()).isEqualTo(java.time.Instant.parse("2026-10-04T10:35:00Z"));
        json.put("futureField", "ignored");
        assertThat(mapper.treeToValue(json, TripEvent.class)).isEqualTo(event);
        json.putNull("driverId");
        assertThat(mapper.treeToValue(json, TripEvent.class).driverId()).isNull();
        json.remove("driverId");
        assertThat(mapper.treeToValue(json, TripEvent.class).driverId()).isNull();
        assertThat(mapper.treeToValue(json, TripEvent.class).cancelled()).isTrue();
    }

    @Test
    void registeredParsesSampleAndUnknownFields() throws Exception {
        var json = sample("UserRegistered");
        var event = mapper.treeToValue(json, UserRegisteredEvent.class);
        assertThat(event.userId()).isEqualTo(12345L);
        assertThat(event.role()).isEqualTo("DRIVER");
        json.put("futureField", "ignored");
        assertThat(mapper.treeToValue(json, UserRegisteredEvent.class)).isEqualTo(event);
    }
}
