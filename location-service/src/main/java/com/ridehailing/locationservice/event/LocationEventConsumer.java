package com.ridehailing.locationservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class LocationEventConsumer implements ApplicationRunner {

    private static final String STREAM = "events.locations";
    private static final String CONSUMER_GROUP = "location-service";
    private static final String PROCESSED_EVENTS_KEY = "loc:processed_events";
    private static final long PROCESSED_EVENT_TTL_SECONDS = 86_400; // 24 hours

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String consumerName;
    private volatile Thread consumerThread;

    public LocationEventConsumer(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${spring.application.name}") String appName) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.consumerName = appName + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void run(ApplicationArguments args) {
        createConsumerGroupIfNotExists();
        processPendingMessages();
        startListener();
    }

    private void createConsumerGroupIfNotExists() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0"), CONSUMER_GROUP);
        } catch (Exception e) {
            // Group might already exist
        }
    }

    private void processPendingMessages() {
        try {
            List<MapRecord<String, Object, Object>> pending = redisTemplate.opsForStream()
                    .read(StreamReadOptions.empty().count(100),
                          StreamOffset.create(STREAM, ReadOffset.from("0")));

            if (pending != null) {
                for (MapRecord<String, Object, Object> record : pending) {
                    processRecord(record);
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing pending messages: " + e.getMessage());
        }
    }

    private void startListener() {
        consumerThread = new Thread(this::listenLoop, "location-event-consumer");
        consumerThread.start();
    }

    @PreDestroy
    public void shutdown() {
        if (consumerThread != null && consumerThread.isAlive()) {
            consumerThread.interrupt();
            try {
                consumerThread.join(5000); // Wait up to 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void listenLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .read(Consumer.from(CONSUMER_GROUP, consumerName),
                              StreamReadOptions.empty().count(10).block(Duration.ofSeconds(5)),
                              StreamOffset.create(STREAM, ReadOffset.lastConsumed()));

                if (records != null) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processRecord(record);
                    }
                }
            } catch (Exception e) {
                // Suppress all errors during cleanup (Redis container closes before Spring shutdown in tests)
                // Only log truly unexpected errors
                String msg = e.getMessage();
                if (msg != null &&
                    !msg.contains("timed out") &&
                    !msg.contains("closed") &&
                    !msg.contains("execution") &&
                    !msg.contains("redis")) {
                    System.err.println("Error in consumer loop: " + msg);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        try {
            Object payloadObj = record.getValue().get("payload");
            if (payloadObj == null) {
                acknowledge(record);
                return;
            }

            String payload = payloadObj.toString();
            LocationEvent event = objectMapper.readValue(payload, LocationEvent.class);

            // Idempotency check: skip if already processed
            String eventId = event.eventId().toString();
            Long isNew = redisTemplate.opsForSet().add(PROCESSED_EVENTS_KEY, eventId);
            if (isNew == null || isNew == 0) {
                // Already processed, just acknowledge
                acknowledge(record);
                return;
            }
            // Set TTL on processed events set
            redisTemplate.expire(PROCESSED_EVENTS_KEY, java.time.Duration.ofSeconds(PROCESSED_EVENT_TTL_SECONDS));

            // Process event based on type
            switch (event) {
                case LocationEvent.DriverLocationUpdated e -> handleLocationUpdated(e);
                case LocationEvent.DriverBusyChanged e -> handleBusyChanged(e);
                case LocationEvent.DriverDeleted e -> handleDriverDeleted(e);
                default -> {
                    // Unknown event type, acknowledge to avoid blocking
                }
            }

            acknowledge(record);

        } catch (JsonProcessingException e) {
            System.err.println("Failed to parse event: " + e.getMessage());
            acknowledge(record);
        } catch (Exception e) {
            System.err.println("Error processing LocationEvent: " + e.getMessage());
            // DON'T acknowledge - will retry
        }
    }

    private void handleLocationUpdated(LocationEvent.DriverLocationUpdated event) {
        // Location service owns this data, event is for other services (dispatch, ws-gateway)
        // Location service itself does not need to consume its own events
        // This consumer is kept minimal for future internal analytics if needed
    }

    private void handleBusyChanged(LocationEvent.DriverBusyChanged event) {
        // Event is for other services to know driver busy status changed
        // Location service does not need to react to its own busy/free events
    }

    private void handleDriverDeleted(LocationEvent.DriverDeleted event) {
        // Event is for other services to clean up references to deleted driver
        // Location service already deleted the driver data before publishing this event
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(STREAM, CONSUMER_GROUP, record.getId());
    }
}