package com.ridehailing.dispatchservice.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface TripEvent permits
        TripEvent.TripCreated,
        TripEvent.TripMatching,
        TripEvent.TripAccepted,
        TripEvent.TripPickingUp,
        TripEvent.TripInProgress,
        TripEvent.TripCompleted,
        TripEvent.TripCancelled,
        TripEvent.NoDriverFound {

    String eventType();
    UUID eventId();
    UUID tripId();
    Instant timestamp();

    record TripCreated(
            UUID eventId,
            UUID tripId,
            long customerId,
            double pickupLat,
            double pickupLng,
            double dropoffLat,
            double dropoffLng,
            long distanceM,
            long fare,
            Instant timestamp
    ) implements TripEvent {
        @Override
        public String eventType() { return "TRIP_CREATED"; }
    }

    record TripMatching(UUID eventId, UUID tripId, Instant timestamp) implements TripEvent {
        @Override
        public String eventType() { return "TRIP_MATCHING"; }
    }

    record TripAccepted(UUID eventId, UUID tripId, long driverId, Instant timestamp) implements TripEvent {
        @Override
        public String eventType() { return "TRIP_ACCEPTED"; }
    }

    record TripPickingUp(UUID eventId, UUID tripId, long driverId, Instant timestamp) implements TripEvent {
        @Override
        public String eventType() { return "TRIP_PICKING_UP"; }
    }

    record TripInProgress(UUID eventId, UUID tripId, long driverId, Instant timestamp) implements TripEvent {
        @Override
        public String eventType() { return "TRIP_IN_PROGRESS"; }
    }

    record TripCompleted(UUID eventId, UUID tripId, long driverId, long fare, Instant timestamp) implements TripEvent {
        @Override
        public String eventType() { return "TRIP_COMPLETED"; }
    }

    record TripCancelled(
            UUID eventId,
            UUID tripId,
            Long customerId,
            Long driverId,
            TripStatus fromStatus,
            String reason,
            Instant timestamp
    ) implements TripEvent {
        @Override
        public String eventType() { return "TRIP_CANCELLED"; }
    }

    record NoDriverFound(UUID eventId, UUID tripId, Instant timestamp) implements TripEvent {
        @Override
        public String eventType() { return "NO_DRIVER_FOUND"; }
    }
}
