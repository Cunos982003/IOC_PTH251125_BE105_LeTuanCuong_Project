package com.ridehailing.dispatchservice.service;

import com.ridehailing.dispatchservice.client.LocationClient;
import com.ridehailing.dispatchservice.client.WsGatewayClient;
import com.ridehailing.dispatchservice.domain.Trip;
import com.ridehailing.dispatchservice.domain.TripEvent;
import com.ridehailing.dispatchservice.domain.TripStatus;
import com.ridehailing.dispatchservice.repository.TripRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class MatchingService {

    private static final int SEARCH_RADIUS_M = 3000;
    private static final int MAX_CANDIDATES = 5;
    private static final Duration OFFER_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final long LOCK_TTL_MS = 20000;

    private final TripRepository tripRepository;
    private final JdbcClient jdbcClient;
    private final LocationClient locationClient;
    private final WsGatewayClient wsGatewayClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> compareAndDeleteScript;
    private final ExecutorService matchExecutor;

    public MatchingService(TripRepository tripRepository,
                          JdbcClient jdbcClient,
                          LocationClient locationClient,
                          WsGatewayClient wsGatewayClient,
                          RedisTemplate<String, String> redisTemplate,
                          DefaultRedisScript<Long> compareAndDeleteScript) {
        this.tripRepository = tripRepository;
        this.jdbcClient = jdbcClient;
        this.locationClient = locationClient;
        this.wsGatewayClient = wsGatewayClient;
        this.redisTemplate = redisTemplate;
        this.compareAndDeleteScript = compareAndDeleteScript;
        this.matchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void startMatching(UUID tripId) {
        matchExecutor.submit(() -> matchLoop(tripId));
    }

    private void matchLoop(UUID tripId) {
        try {
            Trip trip = tripRepository.findById(tripId).orElse(null);
            if (trip == null || trip.status() != TripStatus.MATCHING) {
                return;
            }

            // Get nearby drivers
            List<LocationClient.DriverLocation> candidates;
            try {
                candidates = locationClient.nearby(
                    trip.pickupLat(),
                    trip.pickupLng(),
                    SEARCH_RADIUS_M,
                    MAX_CANDIDATES
                );
            } catch (Exception e) {
                System.err.println("Location service error: " + e.getMessage());
                transitionToNoDriverFound(trip);
                return;
            }

            if (candidates == null || candidates.isEmpty()) {
                transitionToNoDriverFound(trip);
                return;
            }

            // Get drivers already offered for this trip
            Set<Long> alreadyOffered = getAlreadyOfferedDrivers(tripId);

            // Offer to each candidate sequentially
            for (LocationClient.DriverLocation driver : candidates) {
                long driverId = driver.driverId();

                // Skip if already offered
                if (alreadyOffered.contains(driverId)) {
                    continue;
                }

                // Try to acquire lock
                String lockKey = "disp:lock:" + driverId;
                Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, tripId.toString(), Duration.ofMillis(LOCK_TTL_MS));

                if (Boolean.FALSE.equals(locked)) {
                    continue; // Driver has another offer
                }

                try {
                    // Record offer
                    recordOffer(tripId, driverId, "OFFERED");

                    // Push offer to driver
                    try {
                        wsGatewayClient.notifyDriverOffer(driverId, tripId, trip.fare());
                    } catch (Exception e) {
                        System.err.println("Failed to push offer to driver " + driverId + ": " + e.getMessage());
                    }

                    // Wait for response
                    if (waitForResponse(tripId, driverId, lockKey)) {
                        return; // Trip accepted
                    }

                    // Timeout - expire offer and release lock
                    expireOffer(tripId, driverId);
                    releaseLock(lockKey, tripId.toString());

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    releaseLock(lockKey, tripId.toString());
                    return;
                }
            }

            // No driver accepted
            transitionToNoDriverFound(trip);

        } catch (Exception e) {
            System.err.println("Match loop error for trip " + tripId + ": " + e.getMessage());
        }
    }

    private boolean waitForResponse(UUID tripId, long driverId, String lockKey) throws InterruptedException {
        Instant deadline = Instant.now().plus(OFFER_TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            Trip current = tripRepository.findById(tripId).orElse(null);
            if (current == null) {
                return false;
            }

            if (current.status() == TripStatus.ACCEPTED) {
                return true; // Driver accepted
            }

            if (current.status() == TripStatus.CANCELLED) {
                releaseLock(lockKey, tripId.toString());
                return false; // Customer cancelled
            }

            Thread.sleep(POLL_INTERVAL.toMillis());
        }

        return false; // Timeout
    }

    private void releaseLock(String lockKey, String expectedValue) {
        try {
            redisTemplate.execute(compareAndDeleteScript, List.of(lockKey), expectedValue);
        } catch (Exception e) {
            System.err.println("Failed to release lock " + lockKey + ": " + e.getMessage());
        }
    }

    private Set<Long> getAlreadyOfferedDrivers(UUID tripId) {
        return jdbcClient.sql("""
            SELECT driver_id FROM offers WHERE trip_id = ?
        """)
            .param(tripId)
            .query((rs, rowNum) -> rs.getLong("driver_id"))
            .list()
            .stream()
            .collect(Collectors.toSet());
    }

    private void recordOffer(UUID tripId, long driverId, String status) {
        jdbcClient.sql("""
            INSERT INTO offers (trip_id, driver_id, status, offered_at)
            VALUES (?, ?, ?, ?)
        """)
            .param(tripId)
            .param(driverId)
            .param(status)
            .param(Timestamp.from(Instant.now()))
            .update();
    }

    private void expireOffer(UUID tripId, long driverId) {
        jdbcClient.sql("""
            UPDATE offers
            SET status = 'EXPIRED', expired_at = ?
            WHERE trip_id = ? AND driver_id = ? AND status = 'OFFERED'
        """)
            .param(Timestamp.from(Instant.now()))
            .param(tripId)
            .param(driverId)
            .update();
    }

    private void transitionToNoDriverFound(Trip trip) {
        try {
            TripEvent.NoDriverFound event = new TripEvent.NoDriverFound(
                UUID.randomUUID(),
                trip.id(),
                Instant.now()
            );
            tripRepository.transition(trip, TripStatus.NO_DRIVER_FOUND, event);

            // Notify customer
            try {
                wsGatewayClient.notifyTripUpdate(trip.customerId(), trip.id(), "NO_DRIVER_FOUND");
            } catch (Exception e) {
                System.err.println("Failed to notify customer: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("Failed to transition to NO_DRIVER_FOUND: " + e.getMessage());
        }
    }
}
