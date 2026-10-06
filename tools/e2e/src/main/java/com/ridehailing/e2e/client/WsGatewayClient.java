package com.ridehailing.e2e.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public class WsGatewayClient implements AutoCloseable {
    private final String url;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final BlockingQueue<JsonNode> offers = new LinkedBlockingQueue<>();
    private volatile Throwable failure;
    private volatile boolean closing;
    private WebSocket socket;
    public WsGatewayClient(String url) { this.url = url; }
    public void connect(String token) throws Exception {
        socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create(url), new WebSocket.Listener() {
                private final StringBuilder buffer = new StringBuilder();
                public void onOpen(WebSocket ws) { ws.request(1); }
                public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
                    buffer.append(text);
                    if (last) {
                        try {
                            JsonNode json = mapper.readTree(buffer.toString());
                            if ("error".equals(json.path("t").asText())) failure = new IllegalStateException("WS error: " + json);
                            if ("TRIP_REQUEST".equals(json.path("t").asText())) offers.add(json);
                        } catch (Exception e) { failure = e; }
                        buffer.setLength(0);
                    }
                    ws.request(1);
                    return null;
                }
                public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                    if (!closing) failure = new IllegalStateException("WS closed: " + code + " " + reason);
                    return null;
                }
                public void onError(WebSocket ws, Throwable error) { failure = error; }
            }).get(5, TimeUnit.SECONDS);
        send(Map.of("t", "auth", "token", token));
    }
    public void checkHealthy() {
        if (failure != null) throw new IllegalStateException("WebSocket failed", failure);
    }
    private void send(Object message) throws Exception {
        checkHealthy();
        socket.sendText(mapper.writeValueAsString(message), true).get(5, TimeUnit.SECONDS);
    }
    public void sendLocation(double lat, double lng) throws Exception {
        send(Map.of("t", "location", "lat", lat, "lng", lng, "sent_at", System.currentTimeMillis()));
    }
    public JsonNode awaitOffer(UUID id, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        do {
            checkHealthy();
            JsonNode offer = offers.poll(100, TimeUnit.MILLISECONDS);
            if (offer != null) {
                UUID offered = UUID.fromString(offer.path("tripId").asText());
                if (!id.equals(offered)) throw new IllegalStateException("Unexpected trip offer " + offered);
                if (!offer.has("fare")) throw new IllegalStateException("Offer missing fare");
                return offer;
            }
        } while (System.nanoTime() < deadline);
        throw new TimeoutException("No TRIP_REQUEST for " + id);
    }
    public void acceptOffer(UUID id) throws Exception { send(Map.of("t", "accept", "tripId", id.toString())); }
    public void close() {
        closing = true;
        if (socket != null) {
            try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "E2E done").get(2, TimeUnit.SECONDS); }
            catch (Exception e) { socket.abort(); }
        }
        client.close();
    }
}
