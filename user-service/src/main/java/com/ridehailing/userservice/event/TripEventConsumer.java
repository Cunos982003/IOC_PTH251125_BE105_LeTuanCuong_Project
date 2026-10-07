package com.ridehailing.userservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.userservice.repository.TripHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TripEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TripEventConsumer.class);

    private final TripHistoryRepository tripHistoryRepository;
    private final ObjectMapper objectMapper;

    public TripEventConsumer(TripHistoryRepository tripHistoryRepository,
                             ObjectMapper objectMapper) {
        this.tripHistoryRepository = tripHistoryRepository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(id = "tripEventListener", queues = "user.trips", autoStartup = "false")
    public void handleTripEvent(Message message) {
        try {
            String payload = new String(message.getBody());
            String type = message.getMessageProperties().getHeaders().get("type") != null
                    ? message.getMessageProperties().getHeaders().get("type").toString()
                    : message.getMessageProperties().getType();
            log.debug("Received trip event: type={}, payload={}", type, payload);

            if ("trips.completed".equals(type)) {
                handleTripCompleted(payload);
            } else if ("trips.cancelled".equals(type)) {
                handleTripCancelled(payload);
            } else {
                log.warn("Unknown trip event type: {}", type);
            }

        } catch (JsonProcessingException e) {
            log.error("Failed to parse trip event: {}", e.getMessage());
            // Invalid JSON - don't requeue, let it go to DLQ after retries
        } catch (Exception e) {
            log.error("Error processing trip event: {}", e.getMessage(), e);
            // Re-throw to trigger retry/DLQ
            throw e;
        }
    }

    private void handleTripCompleted(String payload) throws JsonProcessingException {
        TripCompletedEvent event = objectMapper.readValue(payload, TripCompletedEvent.class);

        if (event.tripId() != null && event.customerId() != null
                && event.fare() != null && event.completedAt() != null) {
            tripHistoryRepository.insertCompleted(
                    event.tripId(),
                    event.customerId(),
                    event.driverId(),
                    event.fare(),
                    event.completedAt()
            );
            log.info("Recorded trip completed: tripId={}", event.tripId());
        }
    }

    private void handleTripCancelled(String payload) throws JsonProcessingException {
        TripCancelledEvent event = objectMapper.readValue(payload, TripCancelledEvent.class);

        if (event.tripId() != null && event.customerId() != null && event.cancelledAt() != null) {
            tripHistoryRepository.insertCancelled(
                    event.tripId(),
                    event.customerId(),
                    event.driverId(), // can be null
                    event.cancelledAt()
            );
            log.info("Recorded trip cancelled: tripId={}, reason={}", event.tripId(), event.reason());
        }
    }
}