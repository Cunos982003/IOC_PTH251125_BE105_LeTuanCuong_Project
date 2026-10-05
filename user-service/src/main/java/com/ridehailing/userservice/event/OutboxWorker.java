package com.ridehailing.userservice.event;

import com.ridehailing.userservice.repository.OutboxRepository;
import com.ridehailing.userservice.repository.OutboxRepository.OutboxRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final boolean schedulerEnabled;

    public OutboxWorker(OutboxRepository outboxRepository,
                        StringRedisTemplate redisTemplate,
                        @Value("${outbox.scheduler.enabled:true}") boolean schedulerEnabled) {
        this.outboxRepository = outboxRepository;
        this.redisTemplate = redisTemplate;
        this.schedulerEnabled = schedulerEnabled;
    }

    @PostConstruct
    public void init() {
        log.info("OutboxWorker initialized");
        // Run once immediately on startup to process any pending records
        publishPending();
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void scheduledPublish() {
        if (!schedulerEnabled) {
            return;
        }
        doPublish();
    }

    @Transactional
    public void publishPending() {
        doPublish();
    }

    @Transactional
    void doPublish() {
        List<OutboxRecord> records = outboxRepository.fetchPending(50);

        if (!records.isEmpty()) {
            log.debug("OutboxWorker: Found {} pending records", records.size());
        }

        for (OutboxRecord record : records) {
            try {
                // payload is already JSON string from database
                Record<String, Object> streamRecord = StreamRecords.newRecord()
                        .in(record.stream())
                        .ofObject(record.payload());

                redisTemplate.opsForStream().add(streamRecord);
                outboxRepository.markSent(record.id());

                log.debug("OutboxWorker: Published record {} to stream {}", record.id(), record.stream());
            } catch (Exception e) {
                log.error("Failed to publish outbox record {}: {}", record.id(), e.getMessage());
                // Do not mark as sent if failed - will retry on next run
            }
        }
    }
}