package com.ridehailing.userservice.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserRegisteredEvent(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("userId") Long userId,
        @JsonProperty("role") String role,
        @JsonProperty("fullName") String fullName,
        @JsonProperty("registeredAt") Instant registeredAt
) {
}