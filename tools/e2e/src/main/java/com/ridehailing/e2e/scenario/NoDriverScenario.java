package com.ridehailing.e2e.scenario;

import com.ridehailing.e2e.client.ApiGatewayClient;
import com.ridehailing.e2e.model.*;

import java.util.UUID;

public class NoDriverScenario {
    private final ApiGatewayClient apiClient;

    public NoDriverScenario(ApiGatewayClient apiClient) {
        this.apiClient = apiClient;
    }

    public ScenarioResult run() {
        long start = System.currentTimeMillis();
        try {
            String riderEmail = "rider-nodriver-" + UUID.randomUUID() + "@test.com";
            String password = "password123";

            // 1. Đăng ký và đăng nhập rider
            apiClient.register(new RegisterRequest(
                riderEmail, password, "Test Rider No Driver", "+84901000010", "rider"
            ));
            LoginResponse riderLogin = apiClient.login(new LoginRequest(riderEmail, password));

            // 2. Chờ ví được tạo
            WalletResponse wallet = waitForWallet(riderLogin.token(), 5000);
            if (wallet == null) {
                return new ScenarioResult("No Driver", false,
                    System.currentTimeMillis() - start, "Wallet creation timeout");
            }

            // 3. Đặt xe khi không có driver (phải trả NO_DRIVER_FOUND trong ≤25s)
            String idempotencyKey = UUID.randomUUID().toString();
            long requestStart = System.currentTimeMillis();

            try {
                apiClient.requestTrip(
                    riderLogin.token(),
                    new TripRequest(21.0285, 105.8542, 21.0368, 105.8345),
                    idempotencyKey
                );
                return new ScenarioResult("No Driver", false,
                    System.currentTimeMillis() - start,
                    "Expected NO_DRIVER_FOUND error but request succeeded");
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - requestStart;
                if (!e.getMessage().contains("NO_DRIVER_FOUND")) {
                    return new ScenarioResult("No Driver", false,
                        System.currentTimeMillis() - start,
                        "Expected NO_DRIVER_FOUND but got: " + e.getMessage());
                }
                if (elapsed > 25000) {
                    return new ScenarioResult("No Driver", false,
                        System.currentTimeMillis() - start,
                        "NO_DRIVER_FOUND took " + elapsed + "ms (expected ≤25s)");
                }
                return new ScenarioResult("No Driver", true,
                    System.currentTimeMillis() - start,
                    "NO_DRIVER_FOUND in " + elapsed + "ms");
            }

        } catch (Exception e) {
            return new ScenarioResult("No Driver", false,
                System.currentTimeMillis() - start, e.getMessage());
        }
    }

    private WalletResponse waitForWallet(String token, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return apiClient.getWallet(token);
            } catch (Exception e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }
}
