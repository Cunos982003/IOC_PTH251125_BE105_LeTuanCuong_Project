package com.ridehailing.pricingservice.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

@Service
public class DemandService {
    private static final Logger log = LoggerFactory.getLogger(DemandService.class);

    private final StringRedisTemplate redis;
    private final LocationClient locationClient;
    private final Cache<String, Double> surgeCache;

    @Value("${surge.demand-window-minutes}")
    private int demandWindowMinutes;

    @Value("${surge.supply-radius-km}")
    private double supplyRadiusKm;

    @Value("${surge.grid-cell-degrees}")
    private double gridCellDegrees;

    @Value("${surge.demand-ttl-minutes}")
    private int demandTtlMinutes;

    @Value("${surge.cache-seconds}")
    private int cacheSeconds;

    public DemandService(StringRedisTemplate redis, LocationClient locationClient,
                         @Value("${surge.cache-seconds}") int cacheSeconds) {
        this.redis = redis;
        this.locationClient = locationClient;
        this.surgeCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(cacheSeconds))
            .build();
    }

    public void recordDemand(String tripId, double lat, double lng) {
        String cell = cellKey(lat, lng);
        long now = System.currentTimeMillis();

        redis.opsForZSet().add(cell, tripId, now);
        redis.expire(cell, Duration.ofMinutes(demandTtlMinutes));

        // Xóa entries cũ hơn 5 phút
        long cutoff = now - Duration.ofMinutes(5).toMillis();
        redis.opsForZSet().removeRangeByScore(cell, 0, cutoff);
    }

    public double calculateSurge(double lat, double lng) {
        String centerCell = cellKey(lat, lng);

        Double cached = surgeCache.getIfPresent(centerCell);
        if (cached != null) {
            return cached;
        }

        int demand = countDemand(lat, lng);
        int supply = locationClient.getDriverCount(lat, lng, supplyRadiusKm);

        double ratio = demand / (double) Math.max(supply, 1);
        double surge = ratio <= 1.0 ? 1.0 : Math.min(3.0, 1.0 + 0.5 * (ratio - 1.0));

        surgeCache.put(centerCell, surge);
        log.debug("Surge at {}: demand={}, supply={}, ratio={:.2f}, surge={:.2f}",
                  centerCell, demand, supply, ratio, surge);

        return surge;
    }

    private int countDemand(double lat, double lng) {
        long cutoff = System.currentTimeMillis() - Duration.ofMinutes(demandWindowMinutes).toMillis();
        Set<String> allTripIds = new HashSet<>();

        // Ô trung tâm + 8 ô lân cận
        for (int dLat = -1; dLat <= 1; dLat++) {
            for (int dLng = -1; dLng <= 1; dLng++) {
                double neighborLat = lat + dLat * gridCellDegrees;
                double neighborLng = lng + dLng * gridCellDegrees;
                String cell = cellKey(neighborLat, neighborLng);

                Set<String> trips = redis.opsForZSet().rangeByScore(cell, cutoff, Double.MAX_VALUE);
                if (trips != null) {
                    allTripIds.addAll(trips);
                }
            }
        }

        return allTripIds.size();
    }

    private String cellKey(double lat, double lng) {
        long cellLat = (long) Math.floor(lat / gridCellDegrees);
        long cellLng = (long) Math.floor(lng / gridCellDegrees);
        return "price:demand:" + cellLat + "," + cellLng;
    }
}
