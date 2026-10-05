package com.ridehailing.dispatchservice.worker;

import com.ridehailing.dispatchservice.domain.TripEvent;
import com.ridehailing.dispatchservice.domain.TripStatus;
import com.ridehailing.dispatchservice.repository.TripRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class StuckTripScanner {

    private static final Duration MATCHING_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration ACTIVE_WARNING_THRESHOLD = Duration.ofMinutes(30);

    private final JdbcClient jdbcClient;
    private final TripRepository tripRepository;

    public StuckTripScanner(JdbcClient jdbcClient, TripRepository tripRepository) {
        this.jdbcClient = jdbcClient;
        this.tripRepository = tripRepository;
    }

    @Scheduled(fixedRate = 60000) // Every 60 seconds
    public void scanStuckTrips() {
        handleStuckMatchingTrips();
        warnAboutLongActiveTrips();
    }

    private void handleStuckMatchingTrips() {
        Instant cutoff = Instant.now().minus(MATCHING_TIMEOUT);

        List<UUID> stuckTrips = jdbcClient.sql("""
            SELECT id FROM trips
            WHERE status = 'MATCHING'
            AND created_at < ?
        """)
            .param(java.sql.Timestamp.from(cutoff))
            .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
            .list();

        for (UUID tripId : stuckTrips) {
            try {
                transitionToNoDriverFound(tripId);
            } catch (Exception e) {
                System.err.println("Failed to transition stuck trip " + tripId + ": " + e.getMessage());
            }
        }

        if (!stuckTrips.isEmpty()) {
            System.out.println("Transitioned " + stuckTrips.size() + " stuck MATCHING trips to NO_DRIVER_FOUND");
        }
    }

    @Transactional
    protected void transitionToNoDriverFound(UUID tripId) {
        tripRepository.findById(tripId).ifPresent(trip -> {
            if (trip.status() == TripStatus.MATCHING) {
                TripEvent.NoDriverFound event = new TripEvent.NoDriverFound(
                    UUID.randomUUID(),
                    tripId,
                    Instant.now()
                );
                tripRepository.transition(trip, TripStatus.NO_DRIVER_FOUND, event);
            }
        });
    }

    private void warnAboutLongActiveTrips() {
        Instant cutoff = Instant.now().minus(ACTIVE_WARNING_THRESHOLD);

        List<TripWarning> longTrips = jdbcClient.sql("""
            SELECT id, status, driver_id, EXTRACT(EPOCH FROM (NOW() - updated_at)) as age_seconds
            FROM trips
            WHERE status IN ('ACCEPTED', 'PICKING_UP')
            AND updated_at < ?
        """)
            .param(java.sql.Timestamp.from(cutoff))
            .query((rs, rowNum) -> new TripWarning(
                UUID.fromString(rs.getString("id")),
                rs.getString("status"),
                rs.getLong("driver_id"),
                rs.getLong("age_seconds")
            ))
            .list();

        for (TripWarning warning : longTrips) {
            System.err.println(String.format(
                "WARNING: Trip %s in status %s with driver %d for %d seconds",
                warning.tripId(), warning.status(), warning.driverId(), warning.ageSeconds()
            ));
        }
    }

    private record TripWarning(UUID tripId, String status, long driverId, long ageSeconds) {}
}
