package com.ridehailing.dispatchservice.controller;

import com.ridehailing.dispatchservice.client.LocationClient;
import com.ridehailing.dispatchservice.client.WsGatewayClient;
import com.ridehailing.dispatchservice.domain.Trip;
import com.ridehailing.dispatchservice.domain.TripEvent;
import com.ridehailing.dispatchservice.domain.TripStatus;
import com.ridehailing.dispatchservice.repository.TripRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/trips")
public class InternalTripController {

    private static final long LOCK_EXTEND_MS = 20000;

    private final TripRepository tripRepository;
    private final JdbcClient jdbcClient;
    private final LocationClient locationClient;
    private final WsGatewayClient wsGatewayClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> compareAndExtendScript;

    public InternalTripController(TripRepository tripRepository,
                                  JdbcClient jdbcClient,
                                  LocationClient locationClient,
                                  WsGatewayClient wsGatewayClient,
                                  RedisTemplate<String, String> redisTemplate,
                                  DefaultRedisScript<Long> compareAndExtendScript) {
        this.tripRepository = tripRepository;
        this.jdbcClient = jdbcClient;
        this.locationClient = locationClient;
        this.wsGatewayClient = wsGatewayClient;
        this.redisTemplate = redisTemplate;
        this.compareAndExtendScript = compareAndExtendScript;
    }

    @PostMapping("/{tripId}/accept")
    public ResponseEntity<?> acceptTrip(@PathVariable UUID tripId,
                                       @RequestBody AcceptRequest request) {
        long driverId = request.driverId();
        String lockKey = "disp:lock:" + driverId;

        // Check and extend lock
        Long extended = redisTemplate.execute(
            compareAndExtendScript,
            List.of(lockKey),
            tripId.toString(),
            String.valueOf(LOCK_EXTEND_MS)
        );

        if (extended == null || extended == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "INVALID_LOCK", "message", "Lock not held or expired"));
        }

        // Find and update trip
        Trip trip = tripRepository.findById(tripId).orElse(null);
        if (trip == null || trip.status() != TripStatus.MATCHING) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "TRIP_NOT_MATCHING", "message", "Trip not in MATCHING state"));
        }

        try {
            Trip accepted = acceptTripInTransaction(tripId, driverId, trip);

            // After commit: notify location service and customers (best-effort)
            try {
                locationClient.busy(driverId, tripId);
            } catch (Exception e) {
                System.err.println("Failed to mark driver busy: " + e.getMessage());
            }

            try {
                wsGatewayClient.notifyTripUpdate(trip.customerId(), tripId, "ACCEPTED");
            } catch (Exception e) {
                System.err.println("Failed to notify customer: " + e.getMessage());
            }

            return ResponseEntity.ok(Map.of(
                "tripId", tripId.toString(),
                "status", "ACCEPTED"
            ));

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("uq_driver_active_trip") ||
                               msg.contains("duplicate key") ||
                               msg.contains("unique constraint"))) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "DRIVER_ALREADY_BUSY", "message", "Driver already has an active trip"));
            }
            throw e;
        }
    }

    @Transactional
    Trip acceptTripInTransaction(UUID tripId, long driverId, Trip trip) {
        // Assign driver and transition to ACCEPTED
        Trip withDriver = tripRepository.assignDriver(trip, driverId);

        TripEvent.TripAccepted event = new TripEvent.TripAccepted(
            UUID.randomUUID(),
            tripId,
            driverId,
            Instant.now()
        );

        Trip accepted = tripRepository.transition(withDriver, TripStatus.ACCEPTED, event);

        // Update offer status
        jdbcClient.sql("""
            UPDATE offers
            SET status = 'ACCEPTED', accepted_at = ?
            WHERE trip_id = ? AND driver_id = ? AND status = 'OFFERED'
        """)
            .param(Timestamp.from(Instant.now()))
            .param(tripId)
            .param(driverId)
            .update();

        return accepted;
    }

    public record AcceptRequest(long driverId) {}
}
