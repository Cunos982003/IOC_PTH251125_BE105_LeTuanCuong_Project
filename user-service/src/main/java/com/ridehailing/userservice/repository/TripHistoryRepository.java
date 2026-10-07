package com.ridehailing.userservice.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class TripHistoryRepository {

    private final JdbcClient jdbcClient;

    public TripHistoryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertCompleted(UUID tripId, Long customerId, Long driverId, Long fare, Instant completedAt) {
        jdbcClient.sql("""
                        INSERT INTO trip_history (trip_id, customer_id, driver_id, fare, status, completed_at)
                        VALUES (?, ?, ?, ?, 'COMPLETED', ?)
                        ON CONFLICT (trip_id) DO NOTHING
                        """)
                .params(tripId, customerId, driverId, fare, Timestamp.from(completedAt))
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertCancelled(UUID tripId, Long customerId, Long driverId, Instant cancelledAt) {
        jdbcClient.sql("""
                        INSERT INTO trip_history (trip_id, customer_id, driver_id, fare, status, completed_at)
                        VALUES (?, ?, ?, 0, 'CANCELLED', ?)
                        ON CONFLICT (trip_id) DO NOTHING
                        """)
                .params(tripId, customerId, driverId, Timestamp.from(cancelledAt))
                .update();
    }

    public Long countByTripId(UUID tripId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM trip_history WHERE trip_id = ?")
                .param(tripId)
                .query(Long.class)
                .single();
    }
}