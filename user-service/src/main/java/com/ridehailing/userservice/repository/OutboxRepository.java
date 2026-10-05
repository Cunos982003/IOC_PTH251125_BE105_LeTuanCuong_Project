package com.ridehailing.userservice.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.List;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public OutboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void insert(String stream, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            PGobject jsonb = new PGobject();
            jsonb.setType("jsonb");
            jsonb.setValue(json);

            jdbcClient.sql("""
                            INSERT INTO outbox (stream, payload, created_at)
                            VALUES (?, ?, now())
                            """)
                    .param(stream)
                    .param(jsonb)
                    .update();
        } catch (JsonProcessingException | SQLException e) {
            throw new RuntimeException("Failed to insert outbox record", e);
        }
    }

    public List<OutboxRecord> fetchPending(int batchSize) {
        return jdbcClient.sql("""
                        SELECT id, stream, payload::text
                        FROM outbox
                        WHERE sent_at IS NULL
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """)
                .param(batchSize)
                .query((rs, rowNum) -> new OutboxRecord(
                        rs.getLong("id"),
                        rs.getString("stream"),
                        rs.getString("payload")
                ))
                .list();
    }

    public void markSent(Long id) {
        jdbcClient.sql("UPDATE outbox SET sent_at = now() WHERE id = ?")
                .param(id)
                .update();
    }

    public record OutboxRecord(Long id, String stream, String payload) {}
}