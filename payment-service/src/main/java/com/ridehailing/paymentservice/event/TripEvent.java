package com.ridehailing.paymentservice.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TripEvent(
        String eventType,
        UUID tripId,
        Long customerId,
        Long driverId,
        Long fare,
        java.time.Instant completedAt,
        java.time.Instant cancelledAt,
        String reason
) {
    public TripEvent(String eventType, UUID tripId, Long customerId, Long driverId, Long fare) {
        this(eventType, tripId, customerId, driverId, fare, null, null, null);
    }

    public boolean completed() {
        return completedAt != null || "TripCompleted".equals(eventType) || "TRIP_COMPLETED".equals(eventType);
    }

    public boolean cancelled() {
        return cancelledAt != null || "TripCancelled".equals(eventType) || "TRIP_CANCELLED".equals(eventType);
    }
}
