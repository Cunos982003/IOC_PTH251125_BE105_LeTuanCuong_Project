package com.ridehailing.e2e.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public class RedisTestClient {
    private final String host;
    private final int port;
    private final String password;
    private final ObjectMapper mapper = new ObjectMapper();
    public RedisTestClient(String host, int port, String password) {
        this.host = host; this.port = port; this.password = password;
    }
    private Jedis connect() {
        Jedis jedis = new Jedis(host, port, 5000);
        try { if (password != null && !password.isBlank()) jedis.auth(password); return jedis; }
        catch (RuntimeException e) { jedis.close(); throw e; }
    }
    // Replay the producer's exact, valid envelope rather than inventing a record the consumer ignores.
    public void replayCompletedAndAwaitAck(UUID tripId, long customerId, long driverId, long fare) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        Map<String, String> envelope = null;
        try (Jedis jedis = connect()) {
            do {
                for (var entry : jedis.xrevrange("events.trips", StreamEntryID.MAXIMUM_ID, StreamEntryID.MINIMUM_ID, 1000)) {
                    String payload = entry.getFields().get("payload");
                    if (payload == null) continue;
                    JsonNode json = mapper.readTree(payload);
                    if (tripId.toString().equals(json.path("tripId").asText()) && json.hasNonNull("completedAt")) {
                        if (json.path("customerId").asLong() != customerId || json.path("driverId").asLong() != driverId
                            || json.path("fare").asLong() != fare || !json.hasNonNull("eventId"))
                            throw new IOException("Completed event payload does not match settled trip");
                        envelope = entry.getFields();
                        break;
                    }
                }
                if (envelope == null) Thread.sleep(200);
            } while (envelope == null && System.nanoTime() < deadline);
            if (envelope == null) throw new IOException("Actual TripCompleted event not found");
            StreamEntryID replayId = jedis.xadd("events.trips", StreamEntryID.NEW_ENTRY, envelope);
            deadline = System.nanoTime() + 15_000_000_000L;
            do {
                boolean delivered = jedis.xinfoGroups("events.trips").stream().anyMatch(group ->
                    "payment-service".equals(group.getName()) && group.getLastDeliveredId().compareTo(replayId) >= 0);
                if (delivered && jedis.xpending("events.trips", "payment-service", new redis.clients.jedis.params.XPendingParams()
                    .start(replayId).end(replayId).count(1)).isEmpty()) return;
                Thread.sleep(200);
            } while (System.nanoTime() < deadline);
            throw new IOException("Payment consumer did not acknowledge replay " + replayId);
        }
    }
}
