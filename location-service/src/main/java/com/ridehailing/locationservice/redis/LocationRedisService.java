package com.ridehailing.locationservice.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.locationservice.config.ClockProvider;
import com.ridehailing.locationservice.event.LocationEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class LocationRedisService {

    private static final String KEY_GEO = "loc:geo";
    private static final String KEY_LASTSEEN = "loc:lastseen";
    private static final String KEY_BUSY = "loc:busy";
    private static final String KEY_HIST_PREFIX = "loc:hist:";
    private static final String STREAM = "events.locations";

    private static final long STALE_THRESHOLD_MS = 15_000; // 15 seconds
    private static final long KEY_TTL_SECONDS = 30; // TTL for lastseen keys
    private static final long HIST_TTL_SECONDS = 86_400; // 24 hours for history (busy drivers need long history)
    private static final int HISTORY_MAX_ENTRIES = 100; // Keep last 100 location entries per driver
    private static final long LOCATION_HISTORY_SAMPLE_INTERVAL_MS = 10_000; // 10 seconds

    private final StringRedisTemplate stringRedisTemplate;
    private final ClockProvider clockProvider;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    // Track last location_history write time per driver:trip for 10s sampling
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastLocationHistoryWrite = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SAMPLING_CACHE_CLEANUP_AGE_MS = 3_600_000; // 1 hour

    // Lua scripts
    private final DefaultRedisScript<Long> updateLocationScript;
    private final DefaultRedisScript<Long> updateBusyLocationScript;
    private final DefaultRedisScript<Long> cleanupStaleScript;
    private final DefaultRedisScript<Long> markBusyScript;
    private final DefaultRedisScript<Long> markFreeScript;
    private final DefaultRedisScript<Long> deleteDriverScript;
    private final DefaultRedisScript<List<Object>> geosearchNearbyScript;

    public LocationRedisService(StringRedisTemplate stringRedisTemplate,
                                @org.springframework.beans.factory.annotation.Qualifier("clockProvider") ClockProvider clockProvider,
                                JdbcClient jdbcClient,
                                ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.clockProvider = clockProvider;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;

        this.updateLocationScript = createScript("scripts/update_location.lua");
        this.updateBusyLocationScript = createScript("scripts/update_busy_location.lua");
        this.cleanupStaleScript = createScript("scripts/cleanup_stale.lua");
        this.markBusyScript = createScript("scripts/mark_busy.lua");
        this.markFreeScript = createScript("scripts/mark_free.lua");
        this.deleteDriverScript = createScript("scripts/delete_driver.lua");
        this.geosearchNearbyScript = createListScript("scripts/geosearch_nearby.lua");
    }

    private DefaultRedisScript<Long> createScript(String resourcePath) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(resourcePath)));
        script.setResultType(Long.class);
        return script;
    }

    private DefaultRedisScript<List<Object>> createListScript(String resourcePath) {
        DefaultRedisScript<List<Object>> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(resourcePath)));
        script.setResultType((Class<List<Object>>) (Class<?>) List.class);
        return script;
    }

    /**
     * Batch update driver locations.
     * For each entry: validate coordinates, check speed, update Redis atomically via Lua.
     * Uses sentAt from request for timestamp (with validation), falls back to current time.
     * Returns list of driver IDs that were processed.
     */
    @Transactional
    public List<Long> updateLocations(List<LocationUpdate> updates) {
        long now = clockProvider.epochMilli();
        List<Long> processed = new ArrayList<>();

        for (LocationUpdate update : updates) {
            if (!isValidCoordinate(update.lat(), update.lng())) {
                continue;
            }

            // Use sentAt from request if valid (within 1 hour of now), otherwise use current time
            long timestamp = update.sentAt();
            if (timestamp <= 0 || Math.abs(timestamp - now) > 3_600_000) {
                timestamp = now;
            }

            String driverIdStr = String.valueOf(update.driverId());
            String histKey = KEY_HIST_PREFIX + driverIdStr;
            String historyValue = String.format("%.6f,%.6f,%d,%s",
                    update.lat(), update.lng(), timestamp, update.tripId() != null ? update.tripId() : "");

            // Try atomic update for non-busy driver (includes speed check)
            Long result = stringRedisTemplate.execute(updateLocationScript,
                    List.of(KEY_GEO, KEY_LASTSEEN, histKey, KEY_BUSY),
                    driverIdStr,
                    String.valueOf(update.lng()),  // lng first for Redis GEO
                    String.valueOf(update.lat()),
                    String.valueOf(timestamp),
                    historyValue,
                    String.valueOf(KEY_TTL_SECONDS),
                    String.valueOf(HISTORY_MAX_ENTRIES)
            );

            if (result != null && result == 1) {
                // Successfully updated in GEO (not busy, speed OK)
                processed.add(update.driverId());
                writeLocationEventToOutbox(new LocationEvent.DriverLocationUpdated(
                        UUID.randomUUID(), update.driverId(), update.lat(), update.lng(),
                        Instant.ofEpochMilli(timestamp), update.tripId()));
                continue;
            }

            if (result != null && result == -1) {
                // Speed violation - update lastseen and history only (no GEOADD)
                updateBusyLocationAtomic(update.driverId(), timestamp, historyValue, update.tripId());
                // No event for speed violation (not a real location update for nearby)
                continue;
            }

            // result == 0 means driver is busy - update lastseen and history only
            updateBusyLocationAtomic(update.driverId(), timestamp, historyValue, update.tripId());
            processed.add(update.driverId());
        }

        return processed;
    }

    /**
     * Atomically update lastseen and history for busy drivers (no GEOADD)
     * Also writes to location_history every 10 seconds for busy drivers
     */
    private void updateBusyLocationAtomic(long driverId, long timestamp, String historyValue, String tripId) {
        String driverIdStr = String.valueOf(driverId);
        String histKey = KEY_HIST_PREFIX + driverIdStr;

        stringRedisTemplate.execute(updateBusyLocationScript,
                List.of(KEY_LASTSEEN, histKey),
                driverIdStr,
                String.valueOf(timestamp),
                historyValue,
                String.valueOf(KEY_TTL_SECONDS),
                String.valueOf(HISTORY_MAX_ENTRIES)
        );

        // Write to outbox for busy driver location (includes tripId for history)
        writeLocationEventToOutbox(new LocationEvent.DriverLocationUpdated(
                UUID.randomUUID(), driverId,
                Double.parseDouble(historyValue.split(",")[0]),
                Double.parseDouble(historyValue.split(",")[1]),
                Instant.ofEpochMilli(timestamp), tripId));

        // Write to location_history every 10 seconds for busy drivers (sampled)
        if (tripId != null && !tripId.isEmpty()) {
            writeSampledLocationHistory(driverId, tripId, timestamp, historyValue);
        }
    }

    /**
     * Write to location_history table with 10-second sampling
     */
    private void writeSampledLocationHistory(long driverId, String tripId, long timestamp, String historyValue) {
        String sampleKey = String.valueOf(driverId) + ":" + tripId;
        Long lastWrite = lastLocationHistoryWrite.get(sampleKey);
        if (lastWrite == null || (timestamp - lastWrite) >= LOCATION_HISTORY_SAMPLE_INTERVAL_MS) {
            // Update the last write time
            lastLocationHistoryWrite.put(sampleKey, timestamp);
            // Write directly to location_history table (inline to avoid self-invocation issue)
            try {
                String[] parts = historyValue.split(",");
                if (parts.length >= 2) {
                    double lat = Double.parseDouble(parts[0]);
                    double lng = Double.parseDouble(parts[1]);
                    jdbcClient.sql("INSERT INTO location_history (driver_id, trip_id, lat, lng, recorded_at) VALUES (?, ?::uuid, ?, ?, ?)")
                            .param(driverId)
                            .param(tripId)
                            .param(lat)
                            .param(lng)
                            .param(java.sql.Timestamp.from(Instant.ofEpochMilli(timestamp)))
                            .update();
                }
            } catch (Exception e) {
                System.err.println("Failed to write sampled location history for driver " + driverId + ": " + e.getMessage());
            }
        }
    }


    /**
     * Get nearby available drivers using GEOSEARCH (not GEORADIUS).
     * Filters out drivers with lastseen > 15 seconds ago.
     */
    public List<NearbyDriver> getNearbyDrivers(double lat, double lng, double radiusKm, int limit) {
        long now = clockProvider.epochMilli();
        long staleThreshold = now - STALE_THRESHOLD_MS;

        // Use Lua script for GEOSEARCH with proper result parsing
        List<Object> results = stringRedisTemplate.execute(geosearchNearbyScript,
                List.of(KEY_GEO),
                String.valueOf(lng),
                String.valueOf(lat),
                String.valueOf(radiusKm),
                String.valueOf(limit * 2)); // Get extra to account for stale filtering

        List<NearbyDriver> nearby = new ArrayList<>();
        if (results != null) {
            // Results are flat array: [driverId1, distanceM1, driverId2, distanceM2, ...]
            for (int i = 0; i < results.size(); i += 2) {
                if (i + 1 >= results.size()) break;

                String driverIdStr = results.get(i).toString();
                long distanceM = ((Number) results.get(i + 1)).longValue();
                long driverId = Long.parseLong(driverIdStr);

                // Check if driver is stale
                Double lastSeenScore = stringRedisTemplate.opsForZSet().score(KEY_LASTSEEN, driverIdStr);
                if (lastSeenScore != null && lastSeenScore.longValue() >= staleThreshold) {
                    nearby.add(new NearbyDriver(driverId, distanceM));
                    if (nearby.size() >= limit) {
                        break;
                    }
                }
            }
        }

        return nearby;
    }

    public long getDriverCount(double lat, double lng, double radiusKm) {
        long now = clockProvider.epochMilli();
        long staleThreshold = now - STALE_THRESHOLD_MS;

        List<Object> results = stringRedisTemplate.execute(geosearchNearbyScript,
                List.of(KEY_GEO),
                String.valueOf(lng),
                String.valueOf(lat),
                String.valueOf(radiusKm),
                "1000"); // Large count for counting

        if (results == null) {
            return 0;
        }

        long count = 0;
        // Results are flat array: [driverId1, distanceM1, driverId2, distanceM2, ...]
        for (int i = 0; i < results.size(); i += 2) {
            if (i + 1 >= results.size()) break;

            String driverIdStr = results.get(i).toString();
            Double lastSeenScore = stringRedisTemplate.opsForZSet().score(KEY_LASTSEEN, driverIdStr);
            if (lastSeenScore != null && lastSeenScore.longValue() >= staleThreshold) {
                count++;
            }
        }
        return count;
    }

    public void markBusy(long driverId, String tripId) {
        String driverIdStr = String.valueOf(driverId);
        long now = clockProvider.epochMilli();

        stringRedisTemplate.execute(markBusyScript,
                List.of(KEY_GEO, KEY_BUSY),
                driverIdStr,
                tripId != null ? tripId : "");

        // Write to outbox
        writeLocationEventToOutbox(new LocationEvent.DriverBusyChanged(
                UUID.randomUUID(), driverId, true, tripId, Instant.ofEpochMilli(now)));
    }

    public void markFree(long driverId) {
        String driverIdStr = String.valueOf(driverId);

        stringRedisTemplate.execute(markFreeScript,
                List.of(KEY_BUSY),
                driverIdStr);

        // Write to outbox
        writeLocationEventToOutbox(new LocationEvent.DriverBusyChanged(
                UUID.randomUUID(), driverId, false, null, Instant.ofEpochMilli(clockProvider.epochMilli())));
    }

    public void deleteDriver(long driverId) {
        String driverIdStr = String.valueOf(driverId);

        stringRedisTemplate.execute(deleteDriverScript,
                List.of(KEY_GEO, KEY_LASTSEEN, KEY_BUSY),
                driverIdStr,
                KEY_HIST_PREFIX);

        // Write to outbox
        writeLocationEventToOutbox(new LocationEvent.DriverDeleted(
                UUID.randomUUID(), driverId, Instant.ofEpochMilli(clockProvider.epochMilli())));
    }

    /**
     * Scheduled cleanup: remove drivers with lastseen > 15 seconds from GEO and LASTSEEN
     * Also cleans up busy keys and history for stale drivers
     */
    public void cleanupStaleDrivers() {
        long now = clockProvider.epochMilli();
        long staleThreshold = now - STALE_THRESHOLD_MS;

        Long cleaned = stringRedisTemplate.execute(cleanupStaleScript,
                List.of(KEY_GEO, KEY_LASTSEEN, KEY_BUSY),
                String.valueOf(staleThreshold),
                KEY_HIST_PREFIX);

        if (cleaned != null && cleaned > 0) {
            // Could log cleanup count
        }

        // Cleanup stale sampling cache entries (>1 hour old)
        cleanupSamplingCache(now);
    }

    /**
     * Remove stale entries from location_history sampling cache
     */
    private void cleanupSamplingCache(long now) {
        long cutoff = now - SAMPLING_CACHE_CLEANUP_AGE_MS;
        lastLocationHistoryWrite.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    // Helper methods
    private boolean isValidCoordinate(double lat, double lng) {
        return lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    @Transactional
    private void writeLocationEventToOutbox(LocationEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            jdbcClient.sql("INSERT INTO outbox (stream, payload) VALUES (?, ?::jsonb)")
                    .param(STREAM)
                    .param(payload)
                    .update();
        } catch (Exception e) {
            System.err.println("Failed to write location event to outbox: " + e.getMessage());
        }
    }

    public record LocationUpdate(long driverId, double lat, double lng, long sentAt, String tripId) {}
    public record NearbyDriver(long driverId, long distanceM) {}
}