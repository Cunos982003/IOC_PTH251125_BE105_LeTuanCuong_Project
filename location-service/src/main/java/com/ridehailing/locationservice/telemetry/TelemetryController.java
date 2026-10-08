package com.ridehailing.locationservice.telemetry;

import com.ridehailing.locationservice.redis.LocationRedisService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/internal")
public class TelemetryController {

    private final LocationRedisService redisService;

    // Allowed services that can call driver management endpoints
    private static final Set<String> ALLOWED_CALLER_SERVICES = Set.of(
            "dispatch-service",
            "user-service",
            "ws-gateway"
    );

    public TelemetryController(LocationRedisService redisService) {
        this.redisService = redisService;
    }

    @PostMapping("/locations")
    public ResponseEntity<?> updateLocations(
            @Valid @RequestBody List<LocationRequest> updates) {

        if (updates == null || updates.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("VALIDATION_ERROR", "Request body must be a non-empty array"));
        }

        if (updates.size() > 1000) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("VALIDATION_ERROR", "Maximum 1000 updates per request"));
        }

        List<LocationRedisService.LocationUpdate> values = updates.stream().map(update ->
                new LocationRedisService.LocationUpdate(update.driverId(), update.lat(), update.lng(),
                        update.getSentAtMillis(), update.tripId())).toList();
        List<Long> processed = redisService.updateLocations(values);
        return ResponseEntity.ok(new LocationsResponse(processed.size()));
    }

    @GetMapping("/drivers/nearby")
    public ResponseEntity<?> getNearbyDrivers(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(name = "radiusM", required = false) Double radiusM,
            @RequestParam(name = "radius_km", required = false) Double radiusKm,
            @RequestParam @Min(1) @Max(50) int limit) {

        double radiusMeters = resolveRadiusMeters(radiusM, radiusKm);

        if (!isValidLat(lat) || !isValidLng(lng) || !isValidRadiusKm(radiusMeters / 1000.0)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("VALIDATION_ERROR", "Invalid coordinates or radius"));
        }

        List<LocationRedisService.NearbyDriver> nearby = redisService.getNearbyDrivers(lat, lng, radiusMeters / 1000.0, limit);
        return ResponseEntity.ok(nearby.stream()
                .map(d -> new NearbyDriverResponse(d.driverId(), d.distanceM()))
                .toList());
    }

    @GetMapping("/drivers/count")
    public ResponseEntity<CountResponse> getDriverCount(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(name = "radiusM", required = false) Double radiusM,
            @RequestParam(name = "radius_km", required = false) Double radiusKm) {

        double radiusMeters = resolveRadiusMeters(radiusM, radiusKm);

        if (!isValidLat(lat) || !isValidLng(lng) || !isValidRadiusKm(radiusMeters / 1000.0)) {
            return ResponseEntity.badRequest()
                    .body(new CountResponse(0));
        }

        long count = redisService.getDriverCount(lat, lng, radiusMeters / 1000.0);
        return ResponseEntity.ok(new CountResponse(count));
    }

    @PostMapping({"/drivers/{id}/busy", "/drivers/{driverId}/busy"})
    public ResponseEntity<?> markBusy(
            @PathVariable(name = "id", required = false) Long id,
            @PathVariable(name = "driverId", required = false) Long driverId,
            @Valid @RequestBody MarkBusyRequest request,
            @RequestHeader(name = "X-Caller-Service", required = false) String callerService) {

        long driverIdValue = id != null ? id : driverId;
        if (driverIdValue == 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("VALIDATION_ERROR", "driverId is required"));
        }

        if (!isAllowedCaller(callerService)) {
            return ResponseEntity.status(403)
                    .body(new ErrorResponse("FORBIDDEN", "Caller service not authorized for this operation"));
        }

        redisService.markBusy(driverIdValue, request.tripId());
        return ResponseEntity.ok(java.util.Map.of("status", "BUSY"));
    }

    @PostMapping({"/drivers/{id}/free", "/drivers/{driverId}/free"})
    public ResponseEntity<?> markFree(
            @PathVariable(name = "id", required = false) Long id,
            @PathVariable(name = "driverId", required = false) Long driverId,
            @RequestHeader(name = "X-Caller-Service", required = false) String callerService) {

        long driverIdValue = id != null ? id : driverId;
        if (driverIdValue == 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("VALIDATION_ERROR", "driverId is required"));
        }

        if (!isAllowedCaller(callerService)) {
            return ResponseEntity.status(403)
                    .body(new ErrorResponse("FORBIDDEN", "Caller service not authorized for this operation"));
        }

        redisService.markFree(driverIdValue);
        return ResponseEntity.ok(java.util.Map.of("status", "ONLINE"));
    }

    @DeleteMapping({"/drivers/{id}", "/drivers/{driverId}"})
    public ResponseEntity<?> deleteDriver(
            @PathVariable(name = "id", required = false) Long id,
            @PathVariable(name = "driverId", required = false) Long driverId,
            @RequestHeader(name = "X-Caller-Service", required = false) String callerService) {

        long driverIdValue = id != null ? id : driverId;
        if (driverIdValue == 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("VALIDATION_ERROR", "driverId is required"));
        }

        if (!isAllowedCaller(callerService)) {
            return ResponseEntity.status(403)
                    .body(new ErrorResponse("FORBIDDEN", "Caller service not authorized for this operation"));
        }

        redisService.deleteDriver(driverIdValue);
        return ResponseEntity.ok().build();
    }

    private double resolveRadiusMeters(Double radiusM, Double radiusKm) {
        if (radiusM != null) {
            return radiusM;
        }
        if (radiusKm != null) {
            return radiusKm * 1000.0;
        }
        return 2000.0; // default 2km
    }

    private boolean isAllowedCaller(String callerService) {
        return callerService != null && ALLOWED_CALLER_SERVICES.contains(callerService);
    }

    private boolean isValidLat(double lat) {
        return lat >= -90 && lat <= 90;
    }

    private boolean isValidLng(double lng) {
        return lng >= -180 && lng <= 180;
    }

    private boolean isValidRadiusKm(double radiusKm) {
        return radiusKm >= 0.1 && radiusKm <= 10;
    }

    // Response DTOs
    public record LocationsResponse(int accepted) {}
    public record ErrorResponse(String code, String message) {}
    public record CountResponse(long count) {}
    public record NearbyDriverResponse(long driverId, long distanceM) {}

    public record MarkBusyRequest(String tripId) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class LocationRequest {
        private long driverId;
        private double lat;
        private double lng;
        private Object sentAt; // Can be Instant (ISO string) or Long (epoch millis)
        private String tripId;

        public long driverId() { return driverId; }
        public double lat() { return lat; }
        public double lng() { return lng; }
        public String tripId() { return tripId; }

        public long getSentAtMillis() {
            if (sentAt == null) {
                return Instant.now().toEpochMilli();
            }
            if (sentAt instanceof Instant instant) {
                return instant.toEpochMilli();
            }
            if (sentAt instanceof Number number) {
                return number.longValue();
            }
            if (sentAt instanceof String str) {
                try {
                    return Instant.parse(str).toEpochMilli();
                } catch (Exception e) {
                    return Instant.now().toEpochMilli();
                }
            }
            return Instant.now().toEpochMilli();
        }

        @JsonProperty("sentAt")
        public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

        @JsonProperty("sent_at")
        public void setSentAtLong(Long sentAt) { this.sentAt = sentAt; }

        // For JSON deserialization
        public void setDriverId(long driverId) { this.driverId = driverId; }
        public void setLat(double lat) { this.lat = lat; }
        public void setLng(double lng) { this.lng = lng; }
        public void setTripId(String tripId) { this.tripId = tripId; }
    }
}