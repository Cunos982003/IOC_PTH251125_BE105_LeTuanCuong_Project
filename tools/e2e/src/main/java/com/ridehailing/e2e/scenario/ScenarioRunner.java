package com.ridehailing.e2e.scenario;

import com.ridehailing.e2e.client.ApiGatewayClient;
import com.ridehailing.e2e.client.RedisTestClient;
import com.ridehailing.e2e.client.WsGatewayClient;
import com.ridehailing.e2e.model.TripRequest;
import com.ridehailing.e2e.model.TripResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ScenarioRunner {
    public static void main(String[] args) {
        String apiGatewayUrl = System.getenv().getOrDefault("API_GATEWAY_URL", "http://localhost:8000");
        String wsGatewayUrl = System.getenv().getOrDefault("WS_GATEWAY_URL", "ws://localhost:8001/ws/driver");
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        String redisPassword = System.getenv().getOrDefault("REDIS_PASSWORD", "");

        System.out.println("=".repeat(80));
        System.out.println("E2E Test Suite - Ride Hailing System");
        System.out.println("=".repeat(80));
        System.out.println("API Gateway: " + apiGatewayUrl);
        System.out.println("WS Gateway: " + wsGatewayUrl);
        System.out.println("Redis: " + redisHost);
        System.out.println("=".repeat(80));
        System.out.println();

        ApiGatewayClient apiClient = new ApiGatewayClient(apiGatewayUrl);
        RedisTestClient redisClient = new RedisTestClient(redisHost,
            Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")), redisPassword);

        // Clean up any leftover driver locations from previous test runs
        System.out.println("Cleaning up driver locations from Redis...");
        redisClient.deleteAllDriverLocations();

        // Warm-up: send a dummy trip request to wake up RabbitMQ consumers and service pipelines
        // This avoids cold-start timeouts on the first real test (Happy Path)
        System.out.println("Running: Warm-up trip...");
        runWarmupTrip(apiClient, wsGatewayUrl, redisClient);
        System.out.println();

        List<ScenarioResult> results = new ArrayList<>();

        // Luồng chính
        System.out.println("Running: Happy Path...");
        HappyPathScenario happyPath = new HappyPathScenario(apiClient, wsGatewayUrl, redisClient);
        results.add(happyPath.run());
        System.out.println();

        // Nhánh phụ
        System.out.println("Running: No Driver Found...");
        NoDriverScenario noDriver = new NoDriverScenario(apiClient);
        results.add(noDriver.run());
        System.out.println();

        System.out.println("Running: Cancellation...");
        CancellationScenario cancellation = new CancellationScenario(apiClient, wsGatewayUrl, redisClient);
        results.add(cancellation.run());
        System.out.println();

        System.out.println("Running: Insufficient Balance...");
        InsufficientBalanceScenario insufficientBalance = new InsufficientBalanceScenario(apiClient, wsGatewayUrl, redisClient);
        results.add(insufficientBalance.run());
        System.out.println();

        // In bảng kết quả
        System.out.println("=".repeat(80));
        System.out.println("RESULTS");
        System.out.println("=".repeat(80));
        System.out.printf("%-30s %-15s %-15s %s%n", "Scenario", "Result", "Duration (ms)", "Message");
        System.out.println("-".repeat(80));

        int passed = 0;
        int failed = 0;

        for (ScenarioResult result : results) {
            String status = result.passed() ? "PASSED" : "FAILED";
            System.out.printf("%-30s %-15s %-15d %s%n",
                result.name(),
                status,
                result.durationMs(),
                result.message()
            );

            if (result.passed()) {
                passed++;
            } else {
                failed++;
            }
        }

        System.out.println("=".repeat(80));
        System.out.printf("Total: %d | Passed: %d | Failed: %d%n", results.size(), passed, failed);
        System.out.println("=".repeat(80));

        try {
            apiClient.close();
        } catch (Exception e) {
            System.err.println("Error closing API client: " + e.getMessage());
        }

        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * Warm-up trip to wake up RabbitMQ consumers and service pipelines.
     * Creates a driver, connects via WebSocket, sends location, requests a trip,
     * and immediately cancels it. This ensures all consumers are active before real tests.
     */
    private static void runWarmupTrip(ApiGatewayClient apiClient, String wsGatewayUrl, RedisTestClient redisClient) {
        try (WsGatewayClient ws = new WsGatewayClient(wsGatewayUrl)) {
            String customer = apiClient.registerAccount("CUSTOMER");
            String driver = apiClient.registerAccount("DRIVER");
            apiClient.awaitBalance(customer, 500_000, 15_000);
            apiClient.awaitBalance(driver, 0, 15_000);
            ws.connect(driver);
            long driverId = apiClient.userId(driver);
            ws.sendLocation(21.0285, 105.8542);
            Thread.sleep(3000);
            redisClient.awaitDriverInGeo(driverId, 10_000);
            // Re-send location to refresh lastseen
            ws.sendLocation(21.0285, 105.8542);
            Thread.sleep(1000);
            ws.checkHealthy();
            // Request trip and immediately cancel to warm up the pipeline
            TripResponse trip = apiClient.requestTrip(customer, new TripRequest(21.0285, 105.8542, 21.0368, 105.8345), UUID.randomUUID().toString());
            apiClient.cancelTrip(customer, trip.tripId());
            apiClient.awaitStatus(customer, trip.tripId(), "CANCELLED", 10_000);
            // Clean up driver location
            redisClient.deleteDriverLocation(driverId);
            System.out.println("Warm-up trip completed successfully");
        } catch (Exception e) {
            // Warm-up failure is not fatal, just log and continue
            System.err.println("Warm-up trip failed (non-fatal): " + e.getMessage());
        }
    }
}
