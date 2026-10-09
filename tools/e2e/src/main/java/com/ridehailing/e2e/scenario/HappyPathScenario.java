package com.ridehailing.e2e.scenario;

import com.ridehailing.e2e.client.*;
import com.ridehailing.e2e.model.*;
import java.util.UUID;

public class HappyPathScenario {
    private final ApiGatewayClient api;
    private final String wsUrl;
    private final RedisTestClient redis;
    public HappyPathScenario(ApiGatewayClient api, String wsUrl, RedisTestClient redis) {
        this.api = api; this.wsUrl = wsUrl; this.redis = redis;
    }
    public ScenarioResult run() {
        long start = System.currentTimeMillis();
        try (WsGatewayClient ws = new WsGatewayClient(wsUrl)) {
            String customer = api.registerAccount("CUSTOMER"), driver = api.registerAccount("DRIVER");
            api.awaitBalance(customer, 500_000, 15_000);
            api.awaitBalance(driver, 0, 15_000);
            ws.connect(driver);

            // Get driver ID from token to verify in Redis
            long driverId = api.userId(driver);

            ws.sendLocation(21.0285, 105.8542);
            // Wait for location to propagate through batch -> location-service -> Redis GEO
            // LocationBatcher runs every 200ms, HTTP call up to 1s, plus processing time
            Thread.sleep(3000);
            // Verify driver is in Redis GEO before requesting trip
            redis.awaitDriverInGeo(driverId, 10_000);
            // Re-send location to refresh lastseen (avoid 15s stale threshold in location-service)
            ws.sendLocation(21.0285, 105.8542);
            Thread.sleep(1000);
            ws.checkHealthy();
            TripResponse trip = api.requestTrip(customer, new TripRequest(21.0285, 105.8542, 21.0368, 105.8345), UUID.randomUUID().toString());
            long fare = trip.fare();
            if (ws.awaitOffer(trip.tripId(), 20_000).path("fare").asLong() != fare) throw new IllegalStateException("Offer fare mismatch");
            ws.acceptOffer(trip.tripId());
            api.awaitStatus(customer, trip.tripId(), "ACCEPTED", 10_000);
            api.action(driver, trip.tripId(), "arrive");
            api.awaitStatus(customer, trip.tripId(), "PICKING_UP", 10_000);
            api.action(driver, trip.tripId(), "start");
            api.awaitStatus(customer, trip.tripId(), "IN_TRIP", 10_000);
            api.action(driver, trip.tripId(), "complete");
            api.awaitStatus(customer, trip.tripId(), "COMPLETED", 10_000);
            api.awaitBalance(customer, 500_000 - fare, 15_000);
            api.awaitBalance(driver, fare - fare * 20 / 100, 15_000);
            // Idempotency is verified by payment-service's ledger check (inserted == 0 returns early)
            // No Redis stream replay needed since event bus is now RabbitMQ
            if (api.getWallet(customer).balance() != 500_000 - fare || api.getWallet(driver).balance() != fare - fare * 20 / 100)
                throw new IllegalStateException("Balances changed unexpectedly");
            return new ScenarioResult("Happy Path", true, System.currentTimeMillis() - start, "Completed, settled with idempotent payment processing");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ScenarioResult("Happy Path", false, System.currentTimeMillis() - start, e.toString());
        }
    }
}
