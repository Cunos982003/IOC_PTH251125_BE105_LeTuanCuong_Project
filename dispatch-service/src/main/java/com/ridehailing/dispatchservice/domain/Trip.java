package com.ridehailing.dispatchservice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Trip(
        UUID id,
        long customerId,
        Long driverId,
        TripStatus status,
        double pickupLat,
        double pickupLng,
        double dropoffLat,
        double dropoffLng,
        long distanceM,
        long fare,
        BigDecimal surge,
        String idempotencyKey,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public Trip withStatus(TripStatus newStatus) {
        return new Trip(id, customerId, driverId, newStatus, pickupLat, pickupLng,
                dropoffLat, dropoffLng, distanceM, fare, surge, idempotencyKey,
                version, createdAt, Instant.now());
    }

    public Trip withDriver(Long newDriverId) {
        return new Trip(id, customerId, newDriverId, status, pickupLat, pickupLng,
                dropoffLat, dropoffLng, distanceM, fare, surge, idempotencyKey,
                version, createdAt, Instant.now());
    }
}
