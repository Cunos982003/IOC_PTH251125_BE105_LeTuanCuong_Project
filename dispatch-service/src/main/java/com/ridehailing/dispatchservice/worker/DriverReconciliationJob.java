package com.ridehailing.dispatchservice.worker;

import com.ridehailing.dispatchservice.client.LocationClient;
import com.ridehailing.dispatchservice.client.WsGatewayClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DriverReconciliationJob {

    private final JdbcClient jdbcClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final LocationClient locationClient;
    private final WsGatewayClient wsGatewayClient;

    public DriverReconciliationJob(JdbcClient jdbcClient,
                                   RedisTemplate<String, String> redisTemplate,
                                   LocationClient locationClient,
                                   WsGatewayClient wsGatewayClient) {
        this.jdbcClient = jdbcClient;
        this.redisTemplate = redisTemplate;
        this.locationClient = locationClient;
        this.wsGatewayClient = wsGatewayClient;
    }

    @Scheduled(fixedRate = 60000) // Every 60 seconds
    public void reconcileBusyDrivers() {
        List<Long> busyDriversInDb = jdbcClient.sql("""
            SELECT DISTINCT driver_id
            FROM trips
            WHERE driver_id IS NOT NULL
            AND status IN ('COMPLETED', 'CANCELLED')
            AND updated_at > NOW() - INTERVAL '10 minutes'
        """)
            .query((rs, rowNum) -> rs.getLong("driver_id"))
            .list();

        for (long driverId : busyDriversInDb) {
            // Check if driver still has active trip
            boolean hasActiveTrip = jdbcClient.sql("""
                SELECT COUNT(*) > 0
                FROM trips
                WHERE driver_id = ?
                AND status IN ('ACCEPTED', 'PICKING_UP', 'IN_TRIP')
            """)
                .param(driverId)
                .query(Boolean.class)
                .single();

            if (!hasActiveTrip) {
                reconcileDriver(driverId);
            }
        }
    }

    private void reconcileDriver(long driverId) {
        try {
            // Free driver in location service
            locationClient.free(driverId);
            System.out.println("Reconciled driver " + driverId + " - marked free");
        } catch (Exception e) {
            System.err.println("Failed to free driver " + driverId + " during reconciliation: " + e.getMessage());
        }

        try {
            // Delete route visualization
            wsGatewayClient.deleteRoute(driverId);
        } catch (Exception e) {
            System.err.println("Failed to delete route for driver " + driverId + " during reconciliation: " + e.getMessage());
        }

        try {
            // Release Redis lock if exists
            String lockKey = "disp:lock:" + driverId;
            redisTemplate.delete(lockKey);
        } catch (Exception e) {
            System.err.println("Failed to delete lock for driver " + driverId + " during reconciliation: " + e.getMessage());
        }
    }
}
