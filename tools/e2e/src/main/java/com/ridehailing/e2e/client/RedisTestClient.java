package com.ridehailing.e2e.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

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

    /** Remove a driver from Redis GEO and related keys (loc:geo, loc:lastseen, loc:busy, loc:hist:{driverId}). */
    public void deleteDriverLocation(long driverId) {
        try (Jedis jedis = connect()) {
            String driverIdStr = String.valueOf(driverId);
            jedis.zrem("loc:geo", driverIdStr);
            jedis.zrem("loc:lastseen", driverIdStr);
            jedis.hdel("loc:busy", driverIdStr);
            jedis.del("loc:hist:" + driverIdStr);
        }
    }

    /** Remove all drivers from Redis GEO and related keys. */
    public void deleteAllDriverLocations() {
        try (Jedis jedis = connect()) {
            List<String> drivers = jedis.zrange("loc:geo", 0, -1);
            if (drivers != null && !drivers.isEmpty()) {
                for (String driverId : drivers) {
                    jedis.zrem("loc:geo", driverId);
                    jedis.zrem("loc:lastseen", driverId);
                    jedis.hdel("loc:busy", driverId);
                    jedis.del("loc:hist:" + driverId);
                }
            }
            // Also clear any remaining lastseen entries that might not be in geo
            List<String> lastSeenDrivers = jedis.zrange("loc:lastseen", 0, -1);
            if (lastSeenDrivers != null) {
                for (String driverId : lastSeenDrivers) {
                    jedis.zrem("loc:lastseen", driverId);
                    jedis.hdel("loc:busy", driverId);
                    jedis.del("loc:hist:" + driverId);
                }
            }
        }
    }

    /** Check if a driver exists in the Redis GEO index (loc:geo). */
    public boolean isDriverInGeo(long driverId) {
        try (Jedis jedis = connect()) {
            return jedis.zscore("loc:geo", String.valueOf(driverId)) != null;
        }
    }

    /** Wait for a driver to appear in Redis GEO index, polling every 200ms up to timeoutMs. */
    public void awaitDriverInGeo(long driverId, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (isDriverInGeo(driverId)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new TimeoutException("Driver " + driverId + " not found in Redis GEO within " + timeoutMs + "ms");
    }
}
