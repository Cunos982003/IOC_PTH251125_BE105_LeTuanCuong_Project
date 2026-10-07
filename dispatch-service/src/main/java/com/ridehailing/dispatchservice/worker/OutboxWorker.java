package com.ridehailing.dispatchservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.dispatchservice.config.RabbitCallbackRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final String EXCHANGE = "events";
    private static final int BATCH_SIZE = 50;
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(3);
    private static final String OUTBOX_PREFIX = "outbox-";

    private final JdbcClient jdbcClient;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final RabbitCallbackRegistry callbackRegistry;
    private final Map<String, CompletableFuture<Void>> pendingConfirms = new ConcurrentHashMap<>();

    public OutboxWorker(JdbcClient jdbcClient,
                        RabbitTemplate rabbitTemplate,
                        ObjectMapper objectMapper,
                        RabbitCallbackRegistry callbackRegistry) {
        this.jdbcClient = jdbcClient;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.callbackRegistry = callbackRegistry;

        // Register confirm handler for outbox events
        callbackRegistry.registerConfirmHandler(OUTBOX_PREFIX, (ack, cause) -> {
            // The future is handled by callbackRegistry.handleConfirm
        });
    }

    @Scheduled(fixedDelay = 500)
    public void processOutbox() {
        List<OutboxRecord> pending = fetchPendingEvents();

        for (OutboxRecord record : pending) {
            try {
                publishWithConfirm(record);
            } catch (Exception e) {
                log.warn("Failed to send outbox event {}: {}", record.id(), e.getMessage());
                // Continue with next event - retry will happen in next iteration
            }
        }
    }

    private void publishWithConfirm(OutboxRecord record) throws Exception {
        String correlationId = OUTBOX_PREFIX + record.id();
        CompletableFuture<Void> confirmFuture = callbackRegistry.registerFuture(correlationId);
        pendingConfirms.put(correlationId, confirmFuture);

        Message message = MessageBuilder.withBody(record.payload().getBytes())
                .setContentType("application/json")
                .setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT)
                .setHeader("eventId", extractEventId(record.payload()))
                .setHeader("type", record.routingKey())
                .setCorrelationId(correlationId)
                .build();

        CorrelationData correlationData = new CorrelationData(correlationId);

        rabbitTemplate.convertAndSend(EXCHANGE, record.routingKey(), message, correlationData);

        // Wait for confirm with timeout
        try {
            confirmFuture.get(CONFIRM_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            // Confirmed - mark as sent
            markAsSent(record.id());
        } catch (TimeoutException e) {
            log.warn("Confirm timeout for outbox event {}", record.id());
            pendingConfirms.remove(correlationId);
            callbackRegistry.removeFuture(correlationId);
            throw new Exception("Confirm timeout", e);
        }
    }

    private String extractEventId(String payload) {
        try {
            Map<?, ?> map = objectMapper.readValue(payload, Map.class);
            Object eventId = map.get("eventId");
            return eventId != null ? eventId.toString() : UUID.randomUUID().toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private List<OutboxRecord> fetchPendingEvents() {
        // FOR UPDATE SKIP LOCKED ensures each worker gets non-overlapping batch
        return jdbcClient.sql("""
            SELECT id, routing_key, payload, created_at
            FROM outbox
            WHERE sent_at IS NULL
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """)
            .param(BATCH_SIZE)
            .query((rs, rowNum) -> new OutboxRecord(
                rs.getLong("id"),
                rs.getString("routing_key"),
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
        String routingKey,
        String payload,
        Instant createdAt
    ) {}
}