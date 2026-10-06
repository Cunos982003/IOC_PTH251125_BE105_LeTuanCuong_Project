package com.ridehailing.userservice;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ridehailing.userservice.event.TripCompletedEvent;
import com.ridehailing.userservice.event.UserRegisteredEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra event records khớp với contract trong docs/contracts/events/
 */
class ContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void userRegisteredEvent_matchesContract() throws IOException {
        // Read contract sample
        InputStream is = getClass().getResourceAsStream("/contracts/events/UserRegistered.json");
        assertThat(is).isNotNull();

        UserRegisteredEvent event = objectMapper.readValue(is, UserRegisteredEvent.class);

        // Verify all fields are present and parsed correctly
        assertThat(event.eventId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(event.userId()).isEqualTo(12345L);
        assertThat(event.role()).isEqualTo("DRIVER");
        assertThat(event.fullName()).isEqualTo("Nguyễn Văn A");
        assertThat(event.registeredAt()).isEqualTo(Instant.parse("2026-10-04T10:30:00Z"));
    }

    @Test
    void userRegisteredEvent_serializesCorrectly() throws IOException {
        UserRegisteredEvent event = new UserRegisteredEvent(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                12345L,
                "DRIVER",
                "Nguyễn Văn A",
                Instant.parse("2026-10-04T10:30:00Z")
        );

        String json = objectMapper.writeValueAsString(event);

        try (InputStream sample = getClass().getResourceAsStream("/contracts/events/UserRegistered.json")) {
            assertThat(sample).isNotNull();
            assertThat(objectMapper.readTree(json)).isEqualTo(objectMapper.readTree(sample));
        }
    }

    @Test
    void tripCompletedEvent_matchesContract() throws IOException {
        // Read contract sample
        InputStream is = getClass().getResourceAsStream("/contracts/events/TripCompleted.json");
        assertThat(is).isNotNull();

        TripCompletedEvent event = objectMapper.readValue(is, TripCompletedEvent.class);

        // Verify all fields are present and parsed correctly
        assertThat(event.eventId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"));
        assertThat(event.tripId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440002"));
        assertThat(event.customerId()).isEqualTo(12345L);
        assertThat(event.driverId()).isEqualTo(67890L);
        assertThat(event.fare()).isEqualTo(150000L);
        assertThat(event.completedAt()).isEqualTo(Instant.parse("2026-10-04T10:45:00Z"));
    }

    @Test
    void tripCompletedEvent_serializesCorrectly() throws IOException {
        TripCompletedEvent event = new TripCompletedEvent(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
                UUID.fromString("550e8400-e29b-41d4-a716-446655440002"),
                12345L,
                67890L,
                150000L,
                Instant.parse("2026-10-04T10:45:00Z")
        );

        String json = objectMapper.writeValueAsString(event);

        // Verify it can be parsed back
        TripCompletedEvent parsed = objectMapper.readValue(json, TripCompletedEvent.class);
        assertThat(parsed).isEqualTo(event);
    }

    @Test
    void tripCompletedEvent_ignoresUnknownFields() throws IOException {
        // JSON with extra field that doesn't exist in contract
        String json = """
                {
                  "eventId": "550e8400-e29b-41d4-a716-446655440001",
                  "tripId": "550e8400-e29b-41d4-a716-446655440002",
                  "customerId": 12345,
                  "driverId": 67890,
                  "fare": 150000,
                  "completedAt": "2026-10-04T10:45:00Z",
                  "unknownField": "should be ignored",
                  "anotherUnknownField": 999
                }
                """;

        // Should parse without error due to @JsonIgnoreProperties(ignoreUnknown = true)
        TripCompletedEvent event = objectMapper.readValue(json, TripCompletedEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"));
        assertThat(event.tripId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440002"));
        assertThat(event.fare()).isEqualTo(150000L);
    }

    @Test
    void userRegisteredEvent_ignoresUnknownFields() throws IOException {
        String json = """
                {
                  "eventId": "550e8400-e29b-41d4-a716-446655440000",
                  "userId": 12345,
                  "role": "DRIVER",
                  "fullName": "Nguyễn Văn A",
                  "registeredAt": "2026-10-04T10:30:00Z",
                  "futureField": "new field added in future version"
                }
                """;

        UserRegisteredEvent event = objectMapper.readValue(json, UserRegisteredEvent.class);

        assertThat(event.userId()).isEqualTo(12345L);
        assertThat(event.role()).isEqualTo("DRIVER");
    }
}