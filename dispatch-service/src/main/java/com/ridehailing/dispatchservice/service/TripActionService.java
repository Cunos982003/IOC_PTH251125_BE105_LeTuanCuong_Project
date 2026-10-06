package com.ridehailing.dispatchservice.service;

import com.ridehailing.dispatchservice.client.LocationClient;
import com.ridehailing.dispatchservice.client.WsGatewayClient;
import com.ridehailing.dispatchservice.domain.Trip;
import com.ridehailing.dispatchservice.domain.TripEvent;
import com.ridehailing.dispatchservice.domain.TripStatus;
import com.ridehailing.dispatchservice.repository.TripRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TripActionService {

    private final TripRepository tripRepository;
    private final LocationClient locationClient;
    private final WsGatewayClient wsGatewayClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> compareAndDeleteScript;

    public TripActionService(TripRepository tripRepository,
                             LocationClient locationClient,
                             WsGatewayClient wsGatewayClient,
                             RedisTemplate<String, String> redisTemplate,
                             DefaultRedisScript<Long> compareAndDeleteScript) {
        this.tripRepository = tripRepository;
        this.locationClient = locationClient;
        this.wsGatewayClient = wsGatewayClient;
        this.redisTemplate = redisTemplate;
        this.compareAndDeleteScript = compareAndDeleteScript;
    }

    @Transactional
    public Trip arrive(UUID tripId, long driverId) {
        Trip trip = tripRepository.findById(tripId)
            .orElseThrow(() -> new TripNotFoundException("Trip not found: " + tripId));

        if (trip.driverId() == null || trip.driverId() != driverId) {
            throw new UnauthorizedException("Not authorized for this trip");
        }

        if (trip.status() != TripStatus.ACCEPTED) {
            throw new InvalidStateException("Trip must be ACCEPTED to arrive");
        }

        TripEvent.TripPickingUp event = new TripEvent.TripPickingUp(
            UUID.randomUUID(),
            tripId,
            driverId,
            Instant.now()
        );

        try {
            Trip updated = tripRepository.transition(trip, TripStatus.PICKING_UP, event);

            // Notify customer
            notifyBestEffort(() -> wsGatewayClient.notifyTripUpdate(trip.customerId(), tripId, "PICKING_UP"));

            return updated;
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            throw new InvalidStateException("Trip state was modified by another operation");
        }
    }

    @Transactional
    public Trip start(UUID tripId, long driverId) {
        Trip trip = tripRepository.findById(tripId)
            .orElseThrow(() -> new TripNotFoundException("Trip not found: " + tripId));

        if (trip.driverId() == null || trip.driverId() != driverId) {
            throw new UnauthorizedException("Not authorized for this trip");
        }

        if (trip.status() != TripStatus.PICKING_UP) {
            throw new InvalidStateException("Trip must be PICKING_UP to start");
        }

        TripEvent.TripInProgress event = new TripEvent.TripInProgress(
            UUID.randomUUID(),
            tripId,
            driverId,
            Instant.now()
        );

        try {
            Trip updated = tripRepository.transition(trip, TripStatus.IN_TRIP, event);

            // Notify customer
            notifyBestEffort(() -> wsGatewayClient.notifyTripUpdate(trip.customerId(), tripId, "IN_TRIP"));

            return updated;
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            throw new InvalidStateException("Trip state was modified by another operation");
        }
    }

    @Transactional
    public Trip complete(UUID tripId, long driverId) {
        Trip trip = tripRepository.findById(tripId)
            .orElseThrow(() -> new TripNotFoundException("Trip not found: " + tripId));

        if (trip.driverId() == null || trip.driverId() != driverId) {
            throw new UnauthorizedException("Not authorized for this trip");
        }

        if (trip.status() != TripStatus.IN_TRIP) {
            throw new InvalidStateException("Trip must be IN_TRIP to complete");
        }

        TripEvent.TripCompleted event = new TripEvent.TripCompleted(
            UUID.randomUUID(),
            tripId,
            trip.customerId(),
            driverId,
            trip.fare(),
            Instant.now()
        );

        try {
            Trip updated = tripRepository.transition(trip, TripStatus.COMPLETED, event);

            // Post-commit cleanup
            cleanupAfterTrip(driverId, tripId, trip.customerId());

            return updated;
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            throw new InvalidStateException("Trip state was modified by another operation");
        }
    }

    @Transactional
    public Trip cancel(UUID tripId, long requesterId, boolean isDriver) {
        Trip trip = tripRepository.findById(tripId)
            .orElseThrow(() -> new TripNotFoundException("Trip not found: " + tripId));

        // Check authorization
        boolean isCustomer = trip.customerId() == requesterId;
        boolean isTripDriver = trip.driverId() != null && trip.driverId() == requesterId;

        if (!isCustomer && !isTripDriver) {
            throw new UnauthorizedException("Not authorized to cancel this trip");
        }

        if (!trip.status().canTransitionTo(TripStatus.CANCELLED)) {
            throw new InvalidStateException("Cannot cancel trip in " + trip.status() + " state");
        }

        TripEvent.TripCancelled event = new TripEvent.TripCancelled(
            UUID.randomUUID(),
            tripId,
            trip.customerId(),
            trip.driverId(),
            isDriver ? "DRIVER_CANCEL" : "CUSTOMER_CANCEL",
            Instant.now()
        );

        try {
            Trip updated = tripRepository.transition(trip, TripStatus.CANCELLED, event);

            // Post-commit cleanup if driver was assigned
            if (trip.driverId() != null) {
                cleanupAfterTrip(trip.driverId(), tripId, trip.customerId());
            }

            return updated;
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            throw new InvalidStateException("Trip state was modified by another operation");
        }
    }

    private void cleanupAfterTrip(long driverId, UUID tripId, long customerId) {
        // Release lock if still held
        String lockKey = "disp:lock:" + driverId;
        notifyBestEffort(() -> {
            redisTemplate.execute(
                compareAndDeleteScript,
                List.of(lockKey),
                tripId.toString()
            );
        });

        // Free driver
        notifyBestEffort(() -> locationClient.free(driverId));

        // Delete route
        notifyBestEffort(() -> wsGatewayClient.deleteRoute(driverId));

        // Notify both parties
        notifyBestEffort(() -> wsGatewayClient.notifyTripUpdate(customerId, tripId, "COMPLETED"));
        notifyBestEffort(() -> wsGatewayClient.notifyTripUpdate(driverId, tripId, "COMPLETED"));
    }

    private void notifyBestEffort(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            System.err.println("Best-effort notification failed: " + e.getMessage());
        }
    }

    public static class TripNotFoundException extends RuntimeException {
        public TripNotFoundException(String message) {
            super(message);
        }
    }

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }

    public static class InvalidStateException extends RuntimeException {
        public InvalidStateException(String message) {
            super(message);
        }
    }
}
