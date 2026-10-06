package com.ridehailing.locationservice.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType", include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = LocationEvent.DriverLocationUpdated.class, name = "DRIVER_LOCATION_UPDATED"),
    @JsonSubTypes.Type(value = LocationEvent.DriverBusyChanged.class, name = "DRIVER_BUSY_CHANGED"),
    @JsonSubTypes.Type(value = LocationEvent.DriverDeleted.class, name = "DRIVER_DELETED")
})
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
            String tripId,
            String eventType
    ) implements LocationEvent {
        public DriverLocationUpdated(UUID eventId, long driverId, double lat, double lng, Instant timestamp, String tripId) {
            this(eventId, driverId, lat, lng, timestamp, tripId, "DRIVER_LOCATION_UPDATED");
        }
    }

    record DriverBusyChanged(
            UUID eventId,
            long driverId,
            boolean busy,
            String tripId,
            Instant timestamp,
            String eventType
    ) implements LocationEvent {
        public DriverBusyChanged(UUID eventId, long driverId, boolean busy, String tripId, Instant timestamp) {
            this(eventId, driverId, busy, tripId, timestamp, "DRIVER_BUSY_CHANGED");
        }
    }

    record DriverDeleted(
            UUID eventId,
            long driverId,
            Instant timestamp,
            String eventType
    ) implements LocationEvent {
        public DriverDeleted(UUID eventId, long driverId, Instant timestamp) {
            this(eventId, driverId, timestamp, "DRIVER_DELETED");
        }
    }
}