package com.ridehailing.e2e.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;

import java.util.HashMap;
import java.util.Map;

public class RedisTestClient {
    private final String host;
    private final String password;
    private final ObjectMapper objectMapper;

    public RedisTestClient(String host, String password) {
        this.host = host;
        this.password = password;
        this.objectMapper = new ObjectMapper();
    }

    public void publishTripCompletedEvent(Long tripId, Long riderId, Long driverId, Long fare) {
        try (Jedis jedis = new Jedis(host, 6379)) {
            if (password != null && !password.isEmpty()) {
                jedis.auth(password);
            }

            Map<String, String> event = new HashMap<>();
            event.put("tripId", String.valueOf(tripId));
            event.put("riderId", String.valueOf(riderId));
            event.put("driverId", String.valueOf(driverId));
            event.put("fare", String.valueOf(fare));
            event.put("timestamp", String.valueOf(System.currentTimeMillis()));

            jedis.xadd("events.trips", StreamEntryID.NEW_ENTRY, event);
            System.out.println("Published TripCompleted event manually: tripId=" + tripId);
        }
    }
}
