package com.ridehailing.dispatchservice.config;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

@Component
public class RabbitCallbackRegistry {

    private final Map<String, CompletableFuture<Void>> confirmFutures = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<Boolean, String>> confirmHandlers = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<String, Message>> returnHandlers = new ConcurrentHashMap<>();

    /**
     * Register a confirm handler for a correlation ID prefix.
     */
    public void registerConfirmHandler(String prefix, BiConsumer<Boolean, String> handler) {
        confirmHandlers.put(prefix, handler);
    }

    /**
     * Register a return handler for a correlation ID prefix.
     */
    public void registerReturnHandler(String prefix, BiConsumer<String, Message> handler) {
        returnHandlers.put(prefix, handler);
    }

    /**
     * Register a future for a correlation ID to wait for confirm.
     */
    public CompletableFuture<Void> registerFuture(String correlationId) {
        return confirmFutures.computeIfAbsent(correlationId, k -> new CompletableFuture<>());
    }

    /**
     * Handle confirm callback - delegates to appropriate handler based on prefix.
     */
    public void handleConfirm(CorrelationData correlationData, boolean ack, String cause) {
        String id = correlationData.getId();
        CompletableFuture<Void> future = confirmFutures.remove(id);

        // Find matching handler by prefix
        for (Map.Entry<String, BiConsumer<Boolean, String>> entry : confirmHandlers.entrySet()) {
            if (id.startsWith(entry.getKey())) {
                entry.getValue().accept(ack, cause);
                break;
            }
        }

        if (future != null) {
            if (ack) {
                future.complete(null);
            } else {
                future.completeExceptionally(new RuntimeException("Nack: " + cause));
            }
        }
    }

    /**
     * Handle return callback - delegates to appropriate handler based on prefix.
     */
    public void handleReturn(ReturnedMessage returned) {
        String id = returned.getMessage().getMessageProperties().getCorrelationId();

        for (Map.Entry<String, BiConsumer<String, Message>> entry : returnHandlers.entrySet()) {
            if (id.startsWith(entry.getKey())) {
                entry.getValue().accept(returned.getReplyText(), returned.getMessage());
                break;
            }
        }
    }

    /**
     * Remove and return the future for a correlation ID.
     */
    public CompletableFuture<Void> removeFuture(String correlationId) {
        return confirmFutures.remove(correlationId);
    }
}