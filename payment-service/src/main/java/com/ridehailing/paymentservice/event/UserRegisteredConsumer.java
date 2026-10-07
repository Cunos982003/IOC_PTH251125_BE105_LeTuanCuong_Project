package com.ridehailing.paymentservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.paymentservice.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UserRegisteredConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredConsumer.class);

    private final WalletService walletService;
    private final ObjectMapper objectMapper;

    public UserRegisteredConsumer(WalletService walletService, ObjectMapper objectMapper) {
        this.walletService = walletService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(id = "payment.users.registered", queues = "payment.users.registered", autoStartup = "false")
    public void handleUserRegistered(Message message) {
        try {
            String payload = new String(message.getBody());
            log.debug("Received UserRegistered event: {}", payload);

            UserRegisteredEvent event = objectMapper.readValue(payload, UserRegisteredEvent.class);

            if (event.userId() != null && event.role() != null) {
                // Customer: 500,000, Driver: 0
                long initialBalance = "CUSTOMER".equals(event.role()) ? 500_000L : 0L;
                walletService.createWallet(event.userId(), initialBalance);
            }

            log.info("Processed UserRegistered event for userId={}, role={}", event.userId(), event.role());

        } catch (JsonProcessingException e) {
            log.error("Failed to parse UserRegistered event: {}", e.getMessage());
            // Invalid JSON - don't requeue, let it go to DLQ after retries
        } catch (Exception e) {
            log.error("Error processing UserRegistered event: {}", e.getMessage(), e);
            // Re-throw to trigger retry/DLQ
            throw e;
        }
    }
}