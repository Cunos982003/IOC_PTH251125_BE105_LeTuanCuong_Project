package com.ridehailing.e2e.scenario;
import com.ridehailing.e2e.client.ApiGatewayClient;
import com.ridehailing.e2e.model.*;
import java.util.UUID;

public class NoDriverScenario {
    private final ApiGatewayClient api;
    public NoDriverScenario(ApiGatewayClient api) { this.api = api; }
    public ScenarioResult run() {
        long start = System.currentTimeMillis();
        try {
            String customer = api.registerAccount("CUSTOMER");
            api.awaitBalance(customer, 500_000, 15_000);
            long requestedAt = System.nanoTime();
            // Singapore is far from every driver registered by this suite.
            TripResponse trip = api.requestTrip(customer, new TripRequest(1.3521, 103.8198, 1.36, 103.83), UUID.randomUUID().toString());
            if (!"MATCHING".equals(trip.status()) && !"NO_DRIVER_FOUND".equals(trip.status()))
                throw new IllegalStateException("Unexpected initial status " + trip.status());
            api.awaitStatus(customer, trip.tripId(), "NO_DRIVER_FOUND", 25_000);
            long elapsed = (System.nanoTime() - requestedAt) / 1_000_000;
            if (elapsed > 25_000) throw new IllegalStateException("No-driver resolution exceeded 25 seconds: " + elapsed);
            api.awaitBalance(customer, 500_000, 3000);
            return new ScenarioResult("No Driver", true, System.currentTimeMillis() - start, "NO_DRIVER_FOUND in " + elapsed + "ms");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ScenarioResult("No Driver", false, System.currentTimeMillis() - start, e.toString());
        }
    }
}
