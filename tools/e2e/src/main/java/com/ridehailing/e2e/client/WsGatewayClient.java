package com.ridehailing.e2e.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class WsGatewayClient {
    private static final Logger log = LoggerFactory.getLogger(WsGatewayClient.class);
    private final String wsUrl;
    private final ObjectMapper objectMapper;
    private WebSocket webSocket;
    private final ConcurrentHashMap<String, Consumer<JsonNode>> handlers;
    private final CountDownLatch connectLatch;
    private volatile boolean connected;

    public WsGatewayClient(String wsUrl) {
        this.wsUrl = wsUrl;
        this.objectMapper = new ObjectMapper();
        this.handlers = new ConcurrentHashMap<>();
        this.connectLatch = new CountDownLatch(1);
        this.connected = false;
    }

    public void connect(String token) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
            .header("Authorization", "Bearer " + token)
            .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                private StringBuilder messageBuffer = new StringBuilder();

                @Override
                public void onOpen(WebSocket webSocket) {
                    log.info("WebSocket connected");
                    connected = true;
                    connectLatch.countDown();
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    messageBuffer.append(data);
                    if (last) {
                        String message = messageBuffer.toString();
                        messageBuffer = new StringBuilder();
                        handleMessage(message);
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    log.info("WebSocket closed: {} {}", statusCode, reason);
                    connected = false;
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    log.error("WebSocket error", error);
                    connected = false;
                }
            });

        this.webSocket = wsFuture.get(5, TimeUnit.SECONDS);
        if (!connectLatch.await(5, TimeUnit.SECONDS)) {
            throw new Exception("WebSocket connection timeout");
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String type = json.has("type") ? json.get("type").asText() : null;
            if (type != null && handlers.containsKey(type)) {
                handlers.get(type).accept(json);
            } else {
                log.debug("Received message: {}", message);
            }
        } catch (Exception e) {
            log.error("Failed to parse message: {}", message, e);
        }
    }

    public void onMessage(String type, Consumer<JsonNode> handler) {
        handlers.put(type, handler);
    }

    public void sendLocation(double lat, double lng) {
        String message = String.format(
            "{\"type\":\"location_update\",\"lat\":%f,\"lng\":%f}",
            lat, lng
        );
        webSocket.sendText(message, true);
        log.debug("Sent location: lat={} lng={}", lat, lng);
    }

    public void acceptOffer(Long tripId) {
        String message = String.format(
            "{\"type\":\"accept_offer\",\"tripId\":%d}",
            tripId
        );
        webSocket.sendText(message, true);
        log.info("Sent accept_offer for tripId={}", tripId);
    }

    public void arrive(Long tripId) {
        String message = String.format(
            "{\"type\":\"arrive\",\"tripId\":%d}",
            tripId
        );
        webSocket.sendText(message, true);
        log.info("Sent arrive for tripId={}", tripId);
    }

    public void startTrip(Long tripId) {
        String message = String.format(
            "{\"type\":\"start\",\"tripId\":%d}",
            tripId
        );
        webSocket.sendText(message, true);
        log.info("Sent start for tripId={}", tripId);
    }

    public void completeTrip(Long tripId) {
        String message = String.format(
            "{\"type\":\"complete\",\"tripId\":%d}",
            tripId
        );
        webSocket.sendText(message, true);
        log.info("Sent complete for tripId={}", tripId);
    }

    public boolean isConnected() {
        return connected;
    }

    public void close() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing");
        }
    }
}
