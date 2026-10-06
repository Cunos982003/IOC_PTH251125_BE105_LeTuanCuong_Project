package com.ridehailing.locationservice.telemetry;

import com.ridehailing.locationservice.redis.LocationRedisService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/internal")
public class TelemetryController {

    private final LocationRedisService redisService;

    // Allowed services that can call driver management endpoints
    private static final Set<String> ALLOWED_CALLER_SERVICES = Set.of(
            "dispatch-service",
            "user-service"
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
                        update.sentAt().toEpochMilli(), null)).toList();
        List<Long> processed = redisService.updateLocations(values);
        return ResponseEntity.ok(new LocationsResponse(processed.size()));
    }

    @GetMapping("/drivers/nearby")
    public ResponseEntity<?> getNearbyDrivers(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(name = "radiusM", defaultValue = "2000") double radiusM,
            @RequestParam @Min(1) @Max(50) int limit) {

        if (!isValidLat(lat) || !isValidLng(lng) || !isValidRadiusKm(radiusM / 1000.0)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("VALIDATION_ERROR", "Invalid coordinates or radius"));
        }

        List<LocationRedisService.NearbyDriver> nearby = redisService.getNearbyDrivers(lat, lng, radiusM / 1000.0, limit);
        return ResponseEntity.ok(nearby);
    }

    @GetMapping("/drivers/count")
    public ResponseEntity<CountResponse> getDriverCount(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(name = "radiusM", defaultValue = "2000") double radiusM) {

        if (!isValidLat(lat) || !isValidLng(lng) || !isValidRadiusKm(radiusM / 1000.0)) {
            return ResponseEntity.badRequest()
                    .body(new CountResponse(0));
        }

        long count = redisService.getDriverCount(lat, lng, radiusM / 1000.0);
        return ResponseEntity.ok(new CountResponse(count));
    }

    @PostMapping("/drivers/{id}/busy")
    public ResponseEntity<?> markBusy(
            @PathVariable long id,
            @Valid @RequestBody MarkBusyRequest request,
            @RequestHeader(name = "X-Caller-Service", required = false) String callerService) {

        if (!"dispatch-service".equals(callerService)) {
            return ResponseEntity.status(403)
                    .body(new ErrorResponse("FORBIDDEN", "Only dispatch-service can mark driver busy"));
        }

        redisService.markBusy(id, request.tripId());
        return ResponseEntity.ok(java.util.Map.of("status", "BUSY"));
    }

    @PostMapping("/drivers/{id}/free")
    public ResponseEntity<?> markFree(
            @PathVariable long id,
            @RequestHeader(name = "X-Caller-Service", required = false) String callerService) {

        if (!"dispatch-service".equals(callerService)) {
            return ResponseEntity.status(403)
                    .body(new ErrorResponse("FORBIDDEN", "Only dispatch-service can mark driver free"));
        }

        redisService.markFree(id);
        return ResponseEntity.ok(java.util.Map.of("status", "ONLINE"));
    }

    @DeleteMapping("/drivers/{id}")
    public ResponseEntity<?> deleteDriver(
            @PathVariable long id,
            @RequestHeader(name = "X-Caller-Service", required = false) String callerService) {

        // Only dispatch-service and user-service can delete drivers
        if (!"dispatch-service".equals(callerService) && !"user-service".equals(callerService)) {
            return ResponseEntity.status(403)
                    .body(new ErrorResponse("FORBIDDEN", "Caller service not authorized for this operation"));
        }

        redisService.deleteDriver(id);
        return ResponseEntity.ok().build();
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
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record LocationRequest(long driverId, double lat, double lng,
                                  @jakarta.validation.constraints.NotNull java.time.Instant sentAt) {}

    public record CountResponse(long count) {}

    public record MarkBusyRequest(String tripId) {}
}