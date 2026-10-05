package com.ridehailing.wsgateway.routing;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RoutingService {
    private static final String ROUTE_PREFIX = "ws:route:";
    private static final Duration ROUTE_TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;

    public RoutingService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void setRoute(String driverId, String customerId) {
        redis.opsForValue().set(ROUTE_PREFIX + driverId, customerId, ROUTE_TTL);
    }

    public void deleteRoute(String driverId) {
        redis.delete(ROUTE_PREFIX + driverId);
    }

    public String getRoute(String driverId) {
        return redis.opsForValue().get(ROUTE_PREFIX + driverId);
    }

    // MGET cho batch lookup
    public Map<String, String> getRoutes(List<String> driverIds) {
        if (driverIds.isEmpty()) {
            return Map.of();
        }

        List<String> keys = driverIds.stream()
                .map(id -> ROUTE_PREFIX + id)
                .toList();

        List<String> customerIds = redis.opsForValue().multiGet(keys);
        if (customerIds == null) {
            return Map.of();
        }

        return driverIds.stream()
                .filter(driverId -> {
                    int idx = driverIds.indexOf(driverId);
                    return customerIds.get(idx) != null;
                })
                .collect(Collectors.toMap(
                        driverId -> driverId,
                        driverId -> customerIds.get(driverIds.indexOf(driverId))
                ));
    }
}
