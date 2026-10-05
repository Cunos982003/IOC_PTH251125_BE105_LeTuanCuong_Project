package com.ridehailing.wsgateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.wsgateway.client.LocationServiceClient;
import com.ridehailing.wsgateway.routing.RoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class LocationBatcher {
    private static final Logger log = LoggerFactory.getLogger(LocationBatcher.class);
    private static final String USER_CHANNEL_PREFIX = "ws:out:";

    private final BlockingQueue<LocationUpdate> queue = new LinkedBlockingQueue<>();
    private final LocationServiceClient locationClient;
    private final RoutingService routingService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final long batchIntervalMs;

    public LocationBatcher(LocationServiceClient locationClient,
                           RoutingService routingService,
                           StringRedisTemplate redis,
                           ObjectMapper objectMapper,
                           @Value("${websocket.location-batch-interval-ms:200}") long batchIntervalMs) {
        this.locationClient = locationClient;
        this.routingService = routingService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.batchIntervalMs = batchIntervalMs;
        startBatchThread();
    }

    public void enqueue(String driverId, double lat, double lng, long sentAt) {
        queue.offer(new LocationUpdate(driverId, lat, lng, sentAt));
    }

    private void startBatchThread() {
        Thread.ofVirtual().name("location-batcher").start(() -> {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(batchIntervalMs);
                    processBatch();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error processing location batch", e);
                }
            }
        });
    }

    private void processBatch() {
        List<LocationUpdate> batch = new ArrayList<>();
        queue.drainTo(batch);

        if (batch.isEmpty()) {
            return;
        }

        // 1. Send to location-service
        List<LocationServiceClient.LocationDto> locations = batch.stream()
                .map(u -> new LocationServiceClient.LocationDto(u.driverId, u.lat, u.lng, u.sentAt))
                .toList();

        try {
            locationClient.batchUpdate(locations);
        } catch (Exception e) {
            log.warn("Failed to send location batch of {} updates: {}", batch.size(), e.getMessage());
        }

        // 2. Lookup routes (MGET để giảm số lần gọi Redis)
        List<String> driverIds = batch.stream().map(LocationUpdate::driverId).distinct().toList();
        Map<String, String> routes = routingService.getRoutes(driverIds);

        if (routes.isEmpty()) {
            return;
        }

        // 3. Publish driver_location to customers
        for (LocationUpdate update : batch) {
            String customerId = routes.get(update.driverId);
            if (customerId != null) {
                publishDriverLocation(customerId, update.lat, update.lng, update.sentAt);
            }
        }
    }

    private void publishDriverLocation(String customerId, double lat, double lng, long sentAt) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("t", "driver_location");
            message.put("lat", lat);
            message.put("lng", lng);
            message.put("sent_at", sentAt); // GIỮ NGUYÊN sent_at gốc

            String json = objectMapper.writeValueAsString(message);
            redis.convertAndSend(USER_CHANNEL_PREFIX + customerId, json);
        } catch (Exception e) {
            log.warn("Failed to publish driver location to customer {}", customerId, e);
        }
    }

    private record LocationUpdate(String driverId, double lat, double lng, long sentAt) {
    }
}
