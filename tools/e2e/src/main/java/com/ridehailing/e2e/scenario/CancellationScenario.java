package com.ridehailing.e2e.scenario;
import com.ridehailing.e2e.client.*;
import com.ridehailing.e2e.model.*;
import java.util.UUID;

public class CancellationScenario {
    private final ApiGatewayClient api;
    private final String wsUrl;
    private final RedisTestClient redis;
    public CancellationScenario(ApiGatewayClient api, String wsUrl, RedisTestClient redis) { this.api = api; this.wsUrl = wsUrl; this.redis = redis; }
    public ScenarioResult run() {
        long start = System.currentTimeMillis();
        try (WsGatewayClient ws = new WsGatewayClient(wsUrl)) {
            String customer = api.registerAccount("CUSTOMER"), driver = api.registerAccount("DRIVER");
            api.awaitBalance(customer, 500_000, 15_000);
            api.awaitBalance(driver, 0, 15_000);
            ws.connect(driver);
            long driverId = api.userId(driver);
            ws.sendLocation(16.0544, 108.2022);
            Thread.sleep(3000);
            redis.awaitDriverInGeo(driverId, 10_000);
            // Re-send location to refresh lastseen (avoid 15s stale threshold in location-service)
            ws.sendLocation(16.0544, 108.2022);
            Thread.sleep(1000);
            ws.checkHealthy();
            TripRequest request = new TripRequest(16.0544, 108.2022, 16.06, 108.21);
            TripResponse first = api.requestTrip(customer, request, UUID.randomUUID().toString());
            ws.awaitOffer(first.tripId(), 20_000);
            // Accept then cancel — ensures matchLoop exits and cleanupAfterTrip releases lock synchronously
            ws.acceptOffer(first.tripId());
            api.awaitStatus(customer, first.tripId(), "ACCEPTED", 10_000);
            api.cancelTrip(customer, first.tripId());
            api.awaitStatus(customer, first.tripId(), "CANCELLED", 10_000);
            // Wait for lock release to propagate
            ws.sendLocation(16.0544, 108.2022);
            Thread.sleep(3000);
            redis.awaitDriverInGeo(driverId, 10_000);
            ws.sendLocation(16.0544, 108.2022);
            Thread.sleep(1000);
            ws.checkHealthy();

            // Request second trip - driver should be available now
            TripResponse second = api.requestTrip(customer, request, UUID.randomUUID().toString());
            ws.awaitOffer(second.tripId(), 30_000);
            api.cancelTrip(customer, second.tripId());
            api.awaitStatus(customer, second.tripId(), "CANCELLED", 10_000);
            api.awaitBalance(customer, 500_000, 3000);
            api.awaitBalance(driver, 0, 3000);
            return new ScenarioResult("Cancellation", true, System.currentTimeMillis() - start, "Driver received second offer after first trip accepted then cancelled; both trips cancelled without charge");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ScenarioResult("Cancellation", false, System.currentTimeMillis() - start, e.toString());
        }
    }
}
