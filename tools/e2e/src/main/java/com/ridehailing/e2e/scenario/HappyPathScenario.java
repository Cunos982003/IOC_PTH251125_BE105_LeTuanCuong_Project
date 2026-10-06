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
            ws.sendLocation(21.0285, 105.8542);
            Thread.sleep(800); // WS location batch is asynchronous and has no acknowledgement.
            ws.checkHealthy();
            TripResponse trip = api.requestTrip(customer, new TripRequest(21.0285, 105.8542, 21.0368, 105.8345), UUID.randomUUID().toString());
            long fare = trip.fare();
            if (ws.awaitOffer(trip.tripId(), 12_000).path("fare").asLong() != fare) throw new IllegalStateException("Offer fare mismatch");
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
            redis.replayCompletedAndAwaitAck(trip.tripId(), api.userId(customer), api.userId(driver), fare);
            if (api.getWallet(customer).balance() != 500_000 - fare || api.getWallet(driver).balance() != fare - fare * 20 / 100)
                throw new IllegalStateException("Balances changed after acknowledged replay");
            return new ScenarioResult("Happy Path", true, System.currentTimeMillis() - start, "Completed, settled and replay acknowledged without duplicate charge");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ScenarioResult("Happy Path", false, System.currentTimeMillis() - start, e.toString());
        }
    }
}
