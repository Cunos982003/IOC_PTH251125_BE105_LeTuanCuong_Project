package com.ridehailing.paymentservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.paymentservice.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TripEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(TripEventsConsumer.class);

    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;

    public TripEventsConsumer(SettlementService settlementService, ObjectMapper objectMapper) {
        this.settlementService = settlementService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(id = "payment.trips.completed", queues = "payment.trips.completed", autoStartup = "false")
    public void handleTripCompleted(Message message) {
        try {
            String payload = new String(message.getBody());
            log.debug("Received TripCompleted event: {}", payload);

            TripEvent event = objectMapper.readValue(payload, TripEvent.class);

            if (event.tripId() != null && event.customerId() != null
                    && event.driverId() != null && event.fare() != null) {
                settlementService.settle(event.tripId(), event.customerId(),
                                        event.driverId(), event.fare());
            }

            log.info("Processed TripCompleted event for tripId={}", event.tripId());

        } catch (JsonProcessingException e) {
            log.error("Failed to parse TripCompleted event: {}", e.getMessage());
            // Invalid JSON - don't requeue, let it go to DLQ after retries
        } catch (IllegalStateException e) {
            // Payment failure (insufficient balance) - acknowledge to avoid retry loop
            log.error("Payment failed: {}", e.getMessage());
            // Don't rethrow - ack the message to avoid retry loop
        } catch (Exception e) {
            log.error("Error processing TripCompleted event: {}", e.getMessage(), e);
            // Re-throw to trigger retry/DLQ
            throw e;
        }
    }

    @RabbitListener(id = "payment.trips.cancelled", queues = "payment.trips.cancelled", autoStartup = "false")
    public void handleTripCancelled(Message message) {
        try {
            String payload = new String(message.getBody());
            log.debug("Received TripCancelled event: {}", payload);

            TripEvent event = objectMapper.readValue(payload, TripEvent.class);

            // Just acknowledge, no payment processing for cancellation
            // (refund logic would go here if needed)

            log.info("Processed TripCancelled event for tripId={}, reason={}", event.tripId(), event.reason());

        } catch (JsonProcessingException e) {
            log.error("Failed to parse TripCancelled event: {}", e.getMessage());
            // Invalid JSON - don't requeue, let it go to DLQ after retries
        } catch (Exception e) {
            log.error("Error processing TripCancelled event: {}", e.getMessage(), e);
            // Re-throw to trigger retry/DLQ
            throw e;
        }
    }
}