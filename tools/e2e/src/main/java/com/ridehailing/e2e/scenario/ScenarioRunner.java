package com.ridehailing.e2e.scenario;

import com.ridehailing.e2e.client.ApiGatewayClient;
import com.ridehailing.e2e.client.RedisTestClient;
import com.ridehailing.e2e.client.WsGatewayClient;

import java.util.ArrayList;
import java.util.List;

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
        CancellationScenario cancellation = new CancellationScenario(apiClient, wsGatewayUrl);
        results.add(cancellation.run());
        System.out.println();

        System.out.println("Running: Insufficient Balance...");
        InsufficientBalanceScenario insufficientBalance = new InsufficientBalanceScenario(apiClient, wsGatewayUrl);
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
}
