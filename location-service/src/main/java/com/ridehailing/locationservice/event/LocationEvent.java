package com.ridehailing.locationservice.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface LocationEvent permits LocationEvent.DriverLocationUpdated, LocationEvent.DriverBusyChanged, LocationEvent.DriverDeleted {

    String eventType();

    UUID eventId();

    Instant timestamp();

    record DriverLocationUpdated(
            UUID eventId,
            long driverId,
            double lat,
            double lng,
            Instant timestamp,
            String tripId
    ) implements LocationEvent {
        @Override
        public String eventType() { return "DRIVER_LOCATION_UPDATED"; }
    }

    record DriverBusyChanged(
            UUID eventId,
            long driverId,
            boolean busy,
            String tripId,
            Instant timestamp
    ) implements LocationEvent {
        @Override
        public String eventType() { return "DRIVER_BUSY_CHANGED"; }
    }

    record DriverDeleted(
            UUID eventId,
            long driverId,
            Instant timestamp
    ) implements LocationEvent {
        @Override
        public String eventType() { return "DRIVER_DELETED"; }
    }
}