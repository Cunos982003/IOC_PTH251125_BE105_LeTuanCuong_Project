package com.ridehailing.userservice.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TripCancelledEvent(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("tripId") UUID tripId,
        @JsonProperty("customerId") Long customerId,
        @JsonProperty("driverId") Long driverId,
        @JsonProperty("reason") String reason,
        @JsonProperty("cancelledAt") Instant cancelledAt
) {
}