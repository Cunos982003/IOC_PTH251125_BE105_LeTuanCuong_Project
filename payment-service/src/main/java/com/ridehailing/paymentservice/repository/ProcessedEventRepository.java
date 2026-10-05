package com.ridehailing.paymentservice.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbcClient;

    public ProcessedEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean isProcessed(String eventId) {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM processed_events WHERE event_id = ?")
                .param(eventId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    @Transactional
    public void markProcessed(String eventId) {
        jdbcClient.sql("INSERT INTO processed_events (event_id, processed_at) VALUES (?, now()) ON CONFLICT (event_id) DO NOTHING")
                .param(eventId)
                .update();
    }
}
