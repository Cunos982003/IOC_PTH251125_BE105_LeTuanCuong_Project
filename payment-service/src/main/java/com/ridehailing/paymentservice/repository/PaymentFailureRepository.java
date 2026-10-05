package com.ridehailing.paymentservice.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public class PaymentFailureRepository {

    private final JdbcClient jdbcClient;

    public PaymentFailureRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID tripId, String reason) {
        jdbcClient.sql("INSERT INTO payment_failures (trip_id, reason, created_at) VALUES (?, ?, now())")
                .param(tripId)
                .param(reason)
                .update();
    }

    public int countFailures(UUID tripId) {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM payment_failures WHERE trip_id = ?")
                .param(tripId)
                .query(Integer.class)
                .single();
        return count != null ? count : 0;
    }
}
