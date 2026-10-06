package com.ridehailing.e2e.scenario;
import com.ridehailing.e2e.client.*;
import com.ridehailing.e2e.model.*;
import java.util.UUID;

public class CancellationScenario {
    private final ApiGatewayClient api;
    private final String wsUrl;
    public CancellationScenario(ApiGatewayClient api, String wsUrl) { this.api = api; this.wsUrl = wsUrl; }
    public ScenarioResult run() {
        long start = System.currentTimeMillis();
        try (WsGatewayClient ws = new WsGatewayClient(wsUrl)) {
            String customer = api.registerAccount("CUSTOMER"), driver = api.registerAccount("DRIVER");
            api.awaitBalance(customer, 500_000, 15_000);
            api.awaitBalance(driver, 0, 15_000);
            ws.connect(driver);
            ws.sendLocation(16.0544, 108.2022);
            Thread.sleep(800);
            ws.checkHealthy();
            TripRequest request = new TripRequest(16.0544, 108.2022, 16.06, 108.21);
            TripResponse first = api.requestTrip(customer, request, UUID.randomUUID().toString());
            ws.awaitOffer(first.tripId(), 12_000);
            ws.acceptOffer(first.tripId());
            api.awaitStatus(customer, first.tripId(), "ACCEPTED", 10_000);
            api.action(driver, first.tripId(), "arrive");
            api.awaitStatus(customer, first.tripId(), "PICKING_UP", 10_000);
            api.cancelTrip(customer, first.tripId());
            api.awaitStatus(customer, first.tripId(), "CANCELLED", 10_000);
            ws.sendLocation(16.0544, 108.2022);
            Thread.sleep(800);
            TripResponse second = api.requestTrip(customer, request, UUID.randomUUID().toString());
            // Receiving a second offer on the same socket proves the cancelled driver was freed.
            ws.awaitOffer(second.tripId(), 12_000);
            api.cancelTrip(customer, second.tripId());
            api.awaitStatus(customer, second.tripId(), "CANCELLED", 10_000);
            api.awaitBalance(customer, 500_000, 3000);
            api.awaitBalance(driver, 0, 3000);
            return new ScenarioResult("Cancellation", true, System.currentTimeMillis() - start, "Driver received second offer; both trips cancelled without charge");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ScenarioResult("Cancellation", false, System.currentTimeMillis() - start, e.toString());
        }
    }
}
