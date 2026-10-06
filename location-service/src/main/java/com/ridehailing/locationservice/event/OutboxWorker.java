package com.ridehailing.locationservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@EnableScheduling
public class OutboxWorker {

    private final JdbcClient jdbcClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public OutboxWorker(JdbcClient jdbcClient, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publishPending() {
        List<OutboxRecord> records = jdbcClient.sql("""
                        SELECT id, stream, payload FROM outbox
                        WHERE sent_at IS NULL
                        ORDER BY id
                        LIMIT 50
                        FOR UPDATE SKIP LOCKED
                        """)
                .query(OutboxRecord.class)
                .list();

        for (OutboxRecord record : records) {
            try {
                Record<String, Object> streamRecord = StreamRecords.newRecord()
                        .in(record.stream())
                        .ofObject(record.payload());

                redisTemplate.opsForStream().add(streamRecord);

                jdbcClient.sql("UPDATE outbox SET sent_at = now() WHERE id = ?")
                        .param(record.id())
                        .update();
            } catch (Exception e) {
                System.err.println("Failed to publish outbox record " + record.id() + ": " + e.getMessage());
            }
        }
    }

    private record OutboxRecord(Long id, String stream, String payload) {
    }
}