package com.ridehailing.dispatchservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class OutboxWorker {

    private static final String STREAM_KEY = "events.trips";
    private static final int BATCH_SIZE = 50;

    private final JdbcClient jdbcClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public OutboxWorker(JdbcClient jdbcClient,
                        RedisTemplate<String, String> redisTemplate,
                        ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 500)
    public void processOutbox() {
        List<OutboxRecord> pending = fetchPendingEvents();

        for (OutboxRecord record : pending) {
            try {
                // XADD to Redis Stream
                RecordId recordId = redisTemplate.opsForStream().add(
                    StreamRecords.newRecord()
                        .in(STREAM_KEY)
                        .ofStrings(Map.of("payload", record.payload()))
                );

                if (recordId != null) {
                    // Mark as sent
                    markAsSent(record.id());
                }
            } catch (Exception e) {
                System.err.println("Failed to send outbox event " + record.id() + ": " + e.getMessage());
                // Continue with next event - retry will happen in next iteration
            }
        }
    }

    private List<OutboxRecord> fetchPendingEvents() {
        // FOR UPDATE SKIP LOCKED ensures each worker gets non-overlapping batch
        return jdbcClient.sql("""
            SELECT id, stream, payload, created_at
            FROM outbox
            WHERE sent_at IS NULL
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """)
            .param(BATCH_SIZE)
            .query((rs, rowNum) -> new OutboxRecord(
                rs.getLong("id"),
                rs.getString("stream"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant()
            ))
            .list();
    }

    @Transactional
    protected void markAsSent(long id) {
        jdbcClient.sql("""
            UPDATE outbox
            SET sent_at = ?
            WHERE id = ?
        """)
            .param(Timestamp.from(Instant.now()))
            .param(id)
            .update();
    }

    private record OutboxRecord(
        long id,
        String stream,
        String payload,
        Instant createdAt
    ) {}
}
