package com.ridehailing.dispatchservice.controller;

import com.ridehailing.dispatchservice.domain.Trip;
import com.ridehailing.dispatchservice.service.DispatchService;
import com.ridehailing.dispatchservice.service.TripActionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class RideController {

    private final DispatchService dispatchService;
    private final TripActionService tripActionService;

    public RideController(DispatchService dispatchService,
                          TripActionService tripActionService) {
        this.dispatchService = dispatchService;
        this.tripActionService = tripActionService;
    }

    @PostMapping("/rides")
    public ResponseEntity<?> createRide(@RequestAttribute("userId") long userId,
                                       @Valid @RequestBody CreateRideRequest request) {
        try {
            Trip trip = dispatchService.createTrip(
                userId,
                request.pickupLat,
                request.pickupLng,
                request.dropoffLat,
                request.dropoffLng,
                request.idempotencyKey
            );

            return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                    "tripId", trip.id().toString(),
                    "status", trip.status().name(),
                    "fare", trip.fare()
                ));
        } catch (DispatchService.InsufficientBalanceException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(Map.of("code", "INSUFFICIENT_BALANCE", "message", e.getMessage()));
        }
    }

    @GetMapping("/rides/{id}")
    public ResponseEntity<?> getRide(@RequestAttribute("userId") long userId,
                                    @PathVariable UUID id) {
        Optional<Trip> trip = dispatchService.getTrip(id, userId);

        if (trip.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "NOT_FOUND", "message", "Trip not found or access denied"));
        }

        Trip t = trip.get();
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("tripId", t.id().toString());
        response.put("status", t.status().name());
        response.put("customerId", t.customerId());
        response.put("driverId", t.driverId());
        response.put("fare", t.fare());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/trips")
    public ResponseEntity<?> createTrip(@RequestAttribute("userId") long userId,
                                       @Valid @RequestBody CreateRideRequest request) {
        return createRide(userId, request);
    }

    public record CreateRideRequest(
        @NotNull Double pickupLat,
        @NotNull Double pickupLng,
        @NotNull Double dropoffLat,
        @NotNull Double dropoffLng,
        @NotBlank String idempotencyKey
    ) {}
}
