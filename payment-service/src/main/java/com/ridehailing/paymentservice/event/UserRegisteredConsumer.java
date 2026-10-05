package com.ridehailing.paymentservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.paymentservice.service.WalletService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class UserRegisteredConsumer implements SmartLifecycle {

    private final StringRedisTemplate redisTemplate;
    private final WalletService walletService;
    private final ObjectMapper objectMapper;
    private final String consumerGroup;
    private final String consumerName;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumerThread;

    public UserRegisteredConsumer(
            StringRedisTemplate redisTemplate,
            WalletService walletService,
            ObjectMapper objectMapper,
            @Value("${spring.application.name:payment-service}") String appName) {
        this.redisTemplate = redisTemplate;
        this.walletService = walletService;
        this.objectMapper = objectMapper;
        this.consumerGroup = appName;
        this.consumerName = appName + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            // Retry creating consumer group (Redis might not be ready yet)
            for (int i = 0; i < 5; i++) {
                try {
                    redisTemplate.opsForStream().createGroup("events.users", ReadOffset.from("0"), consumerGroup);
                    break;
                } catch (Exception e) {
                    // Group might already exist or Redis not ready
                    if (i == 4) {
                        System.err.println("Failed to create consumer group after retries: " + e.getMessage());
                    } else {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }

            processPendingMessages();

            consumerThread = new Thread(this::listenLoop, "user-registered-consumer");
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
        return Integer.MAX_VALUE;
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
            List<MapRecord<String, Object, Object>> pending = redisTemplate.opsForStream()
                    .read(Consumer.from(consumerGroup, consumerName),
                          StreamReadOptions.empty().count(100),
                          StreamOffset.create("events.users", ReadOffset.from("0")));

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
                if (!running.get()) break;

                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .read(Consumer.from(consumerGroup, consumerName),
                              StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                              StreamOffset.create("events.users", ReadOffset.lastConsumed()));

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
                redisTemplate.opsForStream().acknowledge("events.users", consumerGroup, record.getId());
                return;
            }

            String payload = payloadObj.toString();
            UserRegisteredEvent event = objectMapper.readValue(payload, UserRegisteredEvent.class);

            if (event.userId() != null && event.role() != null) {
                // Customer: 500,000, Driver: 0
                long initialBalance = "CUSTOMER".equals(event.role()) ? 500_000L : 0L;
                walletService.createWallet(event.userId(), initialBalance);
            }

            redisTemplate.opsForStream().acknowledge("events.users", consumerGroup, record.getId());

        } catch (JsonProcessingException e) {
            // Move to dead letter and acknowledge
            System.err.println("Failed to parse UserRegistered event: " + e.getMessage());
            moveToDeadLetter(record);
            redisTemplate.opsForStream().acknowledge("events.users", consumerGroup, record.getId());
        } catch (Exception e) {
            // Processing error - don't acknowledge, will retry
            System.err.println("Error processing UserRegistered event: " + e.getMessage());
        }
    }

    private void moveToDeadLetter(MapRecord<String, Object, Object> record) {
        try {
            redisTemplate.opsForStream().add("events.dead", record.getValue());
        } catch (Exception e) {
            System.err.println("Failed to move to dead letter: " + e.getMessage());
        }
    }
}
