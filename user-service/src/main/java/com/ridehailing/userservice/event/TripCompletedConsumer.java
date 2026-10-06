package com.ridehailing.userservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.userservice.repository.TripHistoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TripCompletedConsumer implements SmartLifecycle {

    private final StringRedisTemplate redisTemplate;
    private final TripHistoryRepository tripHistoryRepository;
    private final ObjectMapper objectMapper;
    private final String consumerGroup;
    private final String consumerName;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumerThread;

    public TripCompletedConsumer(StringRedisTemplate redisTemplate,
                                  TripHistoryRepository tripHistoryRepository,
                                  ObjectMapper objectMapper,
                                  @Value("${spring.application.name:user-service}") String appName) {
        this.redisTemplate = redisTemplate;
        this.tripHistoryRepository = tripHistoryRepository;
        this.objectMapper = objectMapper;
        this.consumerGroup = appName; // "user-service"
        this.consumerName = appName + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            // Create consumer group if not exists
            try {
                redisTemplate.opsForStream().createGroup("events.trips", ReadOffset.from("0"), consumerGroup);
            } catch (Exception e) {
                // Group might already exist
            }

            // Process pending messages on startup
            processPendingMessages();

            // Start background listener
            consumerThread = new Thread(this::listenLoop, "trip-completed-consumer");
            consumerThread.start();
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (consumerThread != null) {
                consumerThread.interrupt();
                try {
                    consumerThread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // Start late, stop early
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    private void processPendingMessages() {
        try {
            // Read pending messages for this consumer from the group
            List<MapRecord<String, Object, Object>> pending = redisTemplate.opsForStream()
                    .read(Consumer.from(consumerGroup, consumerName),
                          StreamReadOptions.empty().count(100),
                          StreamOffset.create("events.trips", ReadOffset.from("0")));

            if (pending != null) {
                for (MapRecord<String, Object, Object> record : pending) {
                    processRecord(record);
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing pending messages: " + e.getMessage());
        }
    }

    private void listenLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // Check running flag before blocking read
                if (!running.get()) break;

                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .read(Consumer.from(consumerGroup, consumerName),
                              StreamReadOptions.empty().count(10).block(Duration.ofSeconds(1)),
                              StreamOffset.create("events.trips", ReadOffset.lastConsumed()));

                // Check again after blocking read returns
                if (!running.get()) break;

                if (records != null) {
                    for (MapRecord<String, Object, Object> record : records) {
                        if (!running.get()) break;
                        processRecord(record);
                    }
                }
            } catch (Exception e) {
                if (!running.get()) break;
                if (running.get()) {
                    System.err.println("Error in consumer loop: " + e.getMessage());
                }
                // Short sleep with interrupt check
                try {
                    Thread.sleep(500);
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
                // Acknowledge unknown format
                redisTemplate.opsForStream().acknowledge("events.trips", consumerGroup, record.getId());
                return;
            }

            String payload = payloadObj.toString();
            TripCompletedEvent event = objectMapper.readValue(payload, TripCompletedEvent.class);

            // Only process TripCompleted events
            if (event.completedAt() != null && event.fare() != null && event.tripId() != null
                    && event.customerId() != null && event.driverId() != null) {
                tripHistoryRepository.insertIfNotExists(
                        event.tripId(),
                        event.customerId(),
                        event.driverId(),
                        event.fare(),
                        event.completedAt()
                );
            }

            // Acknowledge after successful processing
            redisTemplate.opsForStream().acknowledge("events.trips", consumerGroup, record.getId());

        } catch (JsonProcessingException e) {
            // Invalid JSON - acknowledge to avoid blocking
            System.err.println("Failed to parse event: " + e.getMessage());
            redisTemplate.opsForStream().acknowledge("events.trips", consumerGroup, record.getId());
        } catch (Exception e) {
            // Processing error - DON'T acknowledge, will retry
            System.err.println("Error processing TripCompleted event: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Cause: " + e.getCause().getMessage());
                if (e.getCause().getCause() != null) {
                    System.err.println("Root cause: " + e.getCause().getCause().getMessage());
                }
            }
        }
    }
}