package com.ridehailing.wsgateway.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DriverOfferConsumer {

    private static final Logger log = LoggerFactory.getLogger(DriverOfferConsumer.class);
    private static final String USER_CHANNEL_PREFIX = "ws:out:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public DriverOfferConsumer(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(id = "ws.offers", queues = "ws.offers", autoStartup = "false")
    public void handleDriverOffer(Message message) {
        try {
            String type = message.getMessageProperties().getHeaders().get("type") != null
                    ? message.getMessageProperties().getHeaders().get("type").toString()
                    : message.getMessageProperties().getType();

            if (!"trips.offered".equals(type)) {
                log.warn("Unknown message type: {}", type);
                return;
            }

            String payload = new String(message.getBody());
            log.debug("Received driver offer: {}", payload);

            DriverOfferedEvent event = objectMapper.readValue(payload, DriverOfferedEvent.class);

            // Check if offer has expired
            if (event.expiresAt().isBefore(Instant.now())) {
                log.info("Offer expired, skipping: tripId={}, driverId={}, expiresAt={}",
                        event.tripId(), event.driverId(), event.expiresAt());
                return; // ack automatically
            }

            // Build WebSocket message: {t:"TRIP_REQUEST", tripId, pickup, fare, expiresAt}
            Map<String, Object> wsMessage = new LinkedHashMap<>();
            wsMessage.put("t", "TRIP_REQUEST");
            wsMessage.put("tripId", event.tripId().toString());
            wsMessage.put("pickup", Map.of("lat", event.pickup().lat(), "lng", event.pickup().lng()));
            wsMessage.put("fare", event.fare());
            wsMessage.put("expiresAt", event.expiresAt().toString());

            String json = objectMapper.writeValueAsString(wsMessage);

            // Publish to Redis channel ws:out:{driverId}
            String channel = USER_CHANNEL_PREFIX + event.driverId();
            redis.convertAndSend(channel, json);

            log.info("Published offer to driver {}: tripId={}, fare={}",
                    event.driverId(), event.tripId(), event.fare());

        } catch (JsonProcessingException e) {
            log.error("Failed to parse driver offer: {}", e.getMessage());
            // Invalid JSON - don't requeue, let it go to DLQ after retries
        } catch (Exception e) {
            log.error("Error processing driver offer: {}", e.getMessage(), e);
            // Re-throw to trigger retry/DLQ
            throw e;
        }
    }
}