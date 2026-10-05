package com.ridehailing.dispatchservice.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.dispatchservice.domain.Trip;
import com.ridehailing.dispatchservice.domain.TripEvent;
import com.ridehailing.dispatchservice.domain.TripStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TripRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public TripRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Trip create(Trip trip) {
        try {
            jdbcClient.sql("""
                INSERT INTO trips (id, customer_id, driver_id, status, pickup_lat, pickup_lng,
                                   dropoff_lat, dropoff_lng, distance_m, fare, surge, idempotency_key,
                                   version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
                .param(trip.id())
                .param(trip.customerId())
                .param(trip.driverId())
                .param(trip.status().name())
                .param(trip.pickupLat())
                .param(trip.pickupLng())
                .param(trip.dropoffLat())
                .param(trip.dropoffLng())
                .param(trip.distanceM())
                .param(trip.fare())
                .param(trip.surge())
                .param(trip.idempotencyKey())
                .param(trip.version())
                .param(Timestamp.from(trip.createdAt()))
                .param(Timestamp.from(trip.updatedAt()))
                .update();
            return trip;
        } catch (DuplicateKeyException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("uq_customer_idempotency")) {
                throw new IllegalStateException("Trip with this idempotency key already exists", e);
            }
            throw e;
        }
    }

    public Optional<Trip> findById(UUID tripId) {
        return jdbcClient.sql("""
            SELECT id, customer_id, driver_id, status, pickup_lat, pickup_lng,
                   dropoff_lat, dropoff_lng, distance_m, fare, surge, idempotency_key,
                   version, created_at, updated_at
            FROM trips
            WHERE id = ?
        """)
            .param(tripId)
            .query((rs, rowNum) -> new Trip(
                UUID.fromString(rs.getString("id")),
                rs.getLong("customer_id"),
                rs.getObject("driver_id") != null ? rs.getLong("driver_id") : null,
                TripStatus.valueOf(rs.getString("status")),
                rs.getDouble("pickup_lat"),
                rs.getDouble("pickup_lng"),
                rs.getDouble("dropoff_lat"),
                rs.getDouble("dropoff_lng"),
                rs.getLong("distance_m"),
                rs.getLong("fare"),
                rs.getBigDecimal("surge"),
                rs.getString("idempotency_key"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            ))
            .optional();
    }

    public Optional<Trip> findByCustomerAndIdempotencyKey(long customerId, String idempotencyKey) {
        return jdbcClient.sql("""
            SELECT id, customer_id, driver_id, status, pickup_lat, pickup_lng,
                   dropoff_lat, dropoff_lng, distance_m, fare, surge, idempotency_key,
                   version, created_at, updated_at
            FROM trips
            WHERE customer_id = ? AND idempotency_key = ?
        """)
            .param(customerId)
            .param(idempotencyKey)
            .query((rs, rowNum) -> new Trip(
                UUID.fromString(rs.getString("id")),
                rs.getLong("customer_id"),
                rs.getObject("driver_id") != null ? rs.getLong("driver_id") : null,
                TripStatus.valueOf(rs.getString("status")),
                rs.getDouble("pickup_lat"),
                rs.getDouble("pickup_lng"),
                rs.getDouble("dropoff_lat"),
                rs.getDouble("dropoff_lng"),
                rs.getLong("distance_m"),
                rs.getLong("fare"),
                rs.getBigDecimal("surge"),
                rs.getString("idempotency_key"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            ))
            .optional();
    }

    @Transactional
    public Trip transition(Trip trip, TripStatus newStatus, TripEvent event) {
        if (!trip.status().canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                String.format("Cannot transition from %s to %s", trip.status(), newStatus)
            );
        }

        Trip updated = trip.withStatus(newStatus);
        int rowsUpdated = jdbcClient.sql("""
            UPDATE trips
            SET status = ?, updated_at = ?, version = version + 1
            WHERE id = ? AND version = ?
        """)
            .param(newStatus.name())
            .param(Timestamp.from(updated.updatedAt()))
            .param(trip.id())
            .param(trip.version())
            .update();

        if (rowsUpdated == 0) {
            throw new OptimisticLockingFailureException(
                "Trip was modified by another transaction: " + trip.id()
            );
        }

        // Record state transition
        jdbcClient.sql("""
            INSERT INTO trip_events (trip_id, from_status, to_status, at)
            VALUES (?, ?, ?, ?)
        """)
            .param(trip.id())
            .param(trip.status().name())
            .param(newStatus.name())
            .param(Timestamp.from(Instant.now()))
            .update();

        // Publish event to outbox
        publishEvent(event);

        return new Trip(
            updated.id(),
            updated.customerId(),
            updated.driverId(),
            updated.status(),
            updated.pickupLat(),
            updated.pickupLng(),
            updated.dropoffLat(),
            updated.dropoffLng(),
            updated.distanceM(),
            updated.fare(),
            updated.surge(),
            updated.idempotencyKey(),
            trip.version() + 1,
            updated.createdAt(),
            updated.updatedAt()
        );
    }

    @Transactional
    public Trip assignDriver(Trip trip, long driverId) {
        Trip updated = trip.withDriver(driverId);
        int rowsUpdated = jdbcClient.sql("""
            UPDATE trips
            SET driver_id = ?, updated_at = ?, version = version + 1
            WHERE id = ? AND version = ?
        """)
            .param(driverId)
            .param(Timestamp.from(updated.updatedAt()))
            .param(trip.id())
            .param(trip.version())
            .update();

        if (rowsUpdated == 0) {
            throw new OptimisticLockingFailureException(
                "Trip was modified by another transaction: " + trip.id()
            );
        }

        return new Trip(
            updated.id(),
            updated.customerId(),
            updated.driverId(),
            updated.status(),
            updated.pickupLat(),
            updated.pickupLng(),
            updated.dropoffLat(),
            updated.dropoffLng(),
            updated.distanceM(),
            updated.fare(),
            updated.surge(),
            updated.idempotencyKey(),
            trip.version() + 1,
            updated.createdAt(),
            updated.updatedAt()
        );
    }

    private void publishEvent(TripEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            jdbcClient.sql("""
                INSERT INTO outbox (stream, payload, created_at)
                VALUES (?, ?::jsonb, ?)
            """)
                .param("events.trips")
                .param(payload)
                .param(Timestamp.from(Instant.now()))
                .update();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event", e);
        }
    }
}
