package com.ridehailing.wsgateway.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DriverOfferedEvent(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("tripId") UUID tripId,
        @JsonProperty("driverId") long driverId,
        @JsonProperty("customerId") long customerId,
        @JsonProperty("pickup") Pickup pickup,
        @JsonProperty("fare") long fare,
        @JsonProperty("expiresAt") Instant expiresAt
) {
    public record Pickup(
            @JsonProperty("lat") double lat,
            @JsonProperty("lng") double lng
    ) {}
}