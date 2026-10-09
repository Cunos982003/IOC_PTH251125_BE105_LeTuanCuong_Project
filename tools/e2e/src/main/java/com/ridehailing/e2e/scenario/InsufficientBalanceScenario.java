package com.ridehailing.e2e.scenario;
import com.ridehailing.e2e.client.*;
import com.ridehailing.e2e.model.*;
import java.util.UUID;

public class InsufficientBalanceScenario {
    private final ApiGatewayClient api;
    private final String wsUrl;
    private final RedisTestClient redis;
    public InsufficientBalanceScenario(ApiGatewayClient api, String wsUrl, RedisTestClient redis) { this.api = api; this.wsUrl = wsUrl; this.redis = redis; }
    public ScenarioResult run() {
        long start = System.currentTimeMillis();
        try (WsGatewayClient ws = new WsGatewayClient(wsUrl)) {
            String customer = api.registerAccount("CUSTOMER"), driver = api.registerAccount("DRIVER");
            api.awaitBalance(customer, 500_000, 15_000);
            api.awaitBalance(driver, 0, 15_000);
            ws.connect(driver);
            long driverId = api.userId(driver);
            long balance = 500_000, earnings = 0;
            // A longer route drains the initial wallet in a bounded number of legitimate trips.
            TripRequest request = new TripRequest(10.7769, 106.7009, 10.90, 106.80);
            for (int completed = 0; completed <= 20; completed++) {
                ws.sendLocation(request.pickupLat(), request.pickupLng());
                Thread.sleep(3000);
                redis.awaitDriverInGeo(driverId, 10_000);
                ws.checkHealthy();
                ApiGatewayClient.HttpResponseInfo response = api.requestTripRaw(customer, request, UUID.randomUUID().toString());
                if (response.status() == 402 && "INSUFFICIENT_BALANCE".equals(api.errorCode(response.body()))) {
                    if (completed == 0) throw new IllegalStateException("Initial 500000 wallet unexpectedly insufficient");
                    api.awaitBalance(customer, balance, 3000);
                    api.awaitBalance(driver, earnings, 3000);
                    return new ScenarioResult("Insufficient Balance", true, System.currentTimeMillis() - start, "HTTP 402 INSUFFICIENT_BALANCE after " + completed + " completed trips");
                }
                TripResponse trip = api.decode(response, TripResponse.class); // All other errors fail, never count as insufficient funds.
                if (completed == 20) {
                    api.cancelTrip(customer, trip.tripId());
                    throw new IllegalStateException("Insufficient balance not reached within 20 trips");
                }
                long fare = trip.fare();
                if (fare <= 0 || fare > balance) throw new IllegalStateException("Invalid affordable fare " + fare);
                ws.awaitOffer(trip.tripId(), 12_000);
                ws.acceptOffer(trip.tripId());
                api.awaitStatus(customer, trip.tripId(), "ACCEPTED", 10_000);
                api.action(driver, trip.tripId(), "arrive");
                api.awaitStatus(customer, trip.tripId(), "PICKING_UP", 10_000);
                api.action(driver, trip.tripId(), "start");
                api.awaitStatus(customer, trip.tripId(), "IN_TRIP", 10_000);
                api.action(driver, trip.tripId(), "complete");
                api.awaitStatus(customer, trip.tripId(), "COMPLETED", 10_000);
                balance -= fare;
                earnings += fare - fare * 20 / 100;
                api.awaitBalance(customer, balance, 15_000);
                api.awaitBalance(driver, earnings, 15_000);
            }
            throw new IllegalStateException("Trip bound exceeded");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ScenarioResult("Insufficient Balance", false, System.currentTimeMillis() - start, e.toString());
        }
    }
}
