package com.ridehailing.locationservice.telemetry;

import com.ridehailing.locationservice.redis.LocationRedisService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class CleanupScheduler {

    private final LocationRedisService redisService;

    public CleanupScheduler(LocationRedisService redisService) {
        this.redisService = redisService;
    }

    @Scheduled(fixedDelay = 5000)
    public void cleanupStaleDrivers() {
        redisService.cleanupStaleDrivers();
    }
}