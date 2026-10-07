package com.ridehailing.userservice.config;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Component
public class RabbitCallbackRegistry {

    private final ConcurrentHashMap<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BiConsumer<Boolean, String>> confirmHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<ReturnedMessage>> returnHandlers = new ConcurrentHashMap<>();

    public void registerConfirmHandler(String prefix, BiConsumer<Boolean, String> handler) {
        confirmHandlers.put(prefix, handler);
    }

    public void registerReturnHandler(String prefix, Consumer<ReturnedMessage> handler) {
        returnHandlers.put(prefix, handler);
    }

    public CompletableFuture<Void> registerFuture(String correlationId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        futures.put(correlationId, future);
        return future;
    }

    public void removeFuture(String correlationId) {
        futures.remove(correlationId);
    }

    public void handleConfirm(CorrelationData correlationData, boolean ack, String cause) {
        String correlationId = correlationData.getId();
        CompletableFuture<Void> future = futures.remove(correlationId);
        if (future != null) {
            if (ack) {
                future.complete(null);
            } else {
                future.completeExceptionally(new Exception("Confirm failed: " + cause));
            }
        }

        // Also call any registered handler
        for (var entry : confirmHandlers.entrySet()) {
            if (correlationId.startsWith(entry.getKey())) {
                entry.getValue().accept(ack, cause);
                break;
            }
        }
    }

    public void handleReturn(ReturnedMessage returned) {
        String correlationId = returned.getMessage().getMessageProperties().getCorrelationId();
        for (var entry : returnHandlers.entrySet()) {
            if (correlationId.startsWith(entry.getKey())) {
                entry.getValue().accept(returned);
                break;
            }
        }
    }
}