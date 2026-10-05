package com.ridehailing.dispatchservice.controller;

import com.ridehailing.dispatchservice.domain.Trip;
import com.ridehailing.dispatchservice.service.TripActionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TripController {

    private final TripActionService tripActionService;

    public TripController(TripActionService tripActionService) {
        this.tripActionService = tripActionService;
    }

    @PostMapping("/trips/{tripId}/arrive")
    public ResponseEntity<?> arrive(@PathVariable UUID tripId,
                                   @RequestAttribute("userId") long userId) {
        try {
            Trip trip = tripActionService.arrive(tripId, userId);
            return ResponseEntity.ok(Map.of(
                "tripId", trip.id().toString(),
                "status", trip.status().name()
            ));
        } catch (TripActionService.TripNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "TRIP_NOT_FOUND", "message", e.getMessage()));
        } catch (TripActionService.UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "FORBIDDEN", "message", e.getMessage()));
        } catch (TripActionService.InvalidStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "INVALID_STATE", "message", e.getMessage()));
        }
    }

    @PostMapping("/trips/{tripId}/start")
    public ResponseEntity<?> start(@PathVariable UUID tripId,
                                  @RequestAttribute("userId") long userId) {
        try {
            Trip trip = tripActionService.start(tripId, userId);
            return ResponseEntity.ok(Map.of(
                "tripId", trip.id().toString(),
                "status", trip.status().name()
            ));
        } catch (TripActionService.TripNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "TRIP_NOT_FOUND", "message", e.getMessage()));
        } catch (TripActionService.UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "FORBIDDEN", "message", e.getMessage()));
        } catch (TripActionService.InvalidStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "INVALID_STATE", "message", e.getMessage()));
        }
    }

    @PostMapping("/trips/{tripId}/complete")
    public ResponseEntity<?> complete(@PathVariable UUID tripId,
                                     @RequestAttribute("userId") long userId) {
        try {
            Trip trip = tripActionService.complete(tripId, userId);
            return ResponseEntity.ok(Map.of(
                "tripId", trip.id().toString(),
                "status", trip.status().name()
            ));
        } catch (TripActionService.TripNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "TRIP_NOT_FOUND", "message", e.getMessage()));
        } catch (TripActionService.UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "FORBIDDEN", "message", e.getMessage()));
        } catch (TripActionService.InvalidStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "INVALID_STATE", "message", e.getMessage()));
        }
    }

    @PostMapping("/rides/{tripId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable UUID tripId,
                                   @RequestAttribute("userId") long userId,
                                   @RequestBody(required = false) CancelRequest request) {
        try {
            boolean isDriver = request != null && Boolean.TRUE.equals(request.isDriver());
            Trip trip = tripActionService.cancel(tripId, userId, isDriver);
            return ResponseEntity.ok(Map.of(
                "tripId", trip.id().toString(),
                "status", trip.status().name()
            ));
        } catch (TripActionService.TripNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "TRIP_NOT_FOUND", "message", e.getMessage()));
        } catch (TripActionService.UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "FORBIDDEN", "message", e.getMessage()));
        } catch (TripActionService.InvalidStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "INVALID_STATE", "message", e.getMessage()));
        }
    }

    public record CancelRequest(Boolean isDriver) {}
}
