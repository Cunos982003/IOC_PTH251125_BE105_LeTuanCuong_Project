package com.ridehailing.e2e.scenario;

import com.ridehailing.e2e.client.ApiGatewayClient;
import com.ridehailing.e2e.client.WsGatewayClient;
import com.ridehailing.e2e.model.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class InsufficientBalanceScenario {
    private final ApiGatewayClient apiClient;
    private final String wsUrl;

    public InsufficientBalanceScenario(ApiGatewayClient apiClient, String wsUrl) {
        this.apiClient = apiClient;
        this.wsUrl = wsUrl;
    }

    public ScenarioResult run() {
        long start = System.currentTimeMillis();
        WsGatewayClient driverWs = null;
        try {
            String riderEmail = "rider-nobalance-" + UUID.randomUUID() + "@test.com";
            String driverEmail = "driver-nobalance-" + UUID.randomUUID() + "@test.com";
            String password = "password123";

            // 1. Đăng ký và đăng nhập
            apiClient.register(new RegisterRequest(
                riderEmail, password, "Test Rider No Balance", "+84901000030", "rider"
            ));
            apiClient.register(new RegisterRequest(
                driverEmail, password, "Test Driver No Balance", "+84901000031", "driver"
            ));

            LoginResponse riderLogin = apiClient.login(new LoginRequest(riderEmail, password));
            LoginResponse driverLogin = apiClient.login(new LoginRequest(driverEmail, password));

            // 2. Chờ ví
            WalletResponse riderWallet = waitForWallet(riderLogin.token(), 5000);
            if (riderWallet == null) {
                return new ScenarioResult("Insufficient Balance", false,
                    System.currentTimeMillis() - start, "Wallet creation timeout");
            }

            // 3. Driver nối WebSocket và gửi vị trí
            driverWs = new WsGatewayClient(wsUrl);
            driverWs.connect(driverLogin.token());
            Thread.sleep(500);

            double driverLat = 21.0285;
            double driverLng = 105.8542;
            driverWs.sendLocation(driverLat, driverLng);
            Thread.sleep(500);

            // 4. Đặt xe nhiều lần để làm hết số dư (initial balance = 100000)
            int tripCount = 0;
            while (true) {
                try {
                    CountDownLatch offerLatch = new CountDownLatch(1);
                    AtomicReference<Long> tripIdRef = new AtomicReference<>();

                    WsGatewayClient finalDriverWs = driverWs;
                    driverWs.onMessage("trip_offer", json -> {
                        tripIdRef.set(json.get("tripId").asLong());
                        offerLatch.countDown();
                    });

                    String idempotencyKey = UUID.randomUUID().toString();
                    TripResponse trip = apiClient.requestTrip(
                        riderLogin.token(),
                        new TripRequest(21.0285, 105.8542, 21.0368, 105.8345),
                        idempotencyKey
                    );

                    if (!offerLatch.await(10, TimeUnit.SECONDS)) {
                        driverWs.close();
                        return new ScenarioResult("Insufficient Balance", false,
                            System.currentTimeMillis() - start, "No trip offer received");
                    }

                    Long tripId = tripIdRef.get();
                    driverWs.acceptOffer(tripId);
                    Thread.sleep(500);
                    driverWs.arrive(tripId);
                    Thread.sleep(500);
                    driverWs.startTrip(tripId);
                    Thread.sleep(500);
                    driverWs.completeTrip(tripId);
                    Thread.sleep(2000);

                    driverWs.sendLocation(driverLat, driverLng);
                    Thread.sleep(500);

                    tripCount++;
                } catch (Exception e) {
                    // Expected: số dư không đủ
                    break;
                }
            }

            // 5. Thử đặt xe khi số dư không đủ → phải trả 402
            String idempotencyKey = UUID.randomUUID().toString();
            ApiGatewayClient.HttpResponseInfo response = apiClient.requestTripRaw(
                riderLogin.token(),
                new TripRequest(21.0285, 105.8542, 21.0368, 105.8345),
                idempotencyKey
            );

            driverWs.close();

            if (response.status() == 402) {
                return new ScenarioResult("Insufficient Balance", true,
                    System.currentTimeMillis() - start,
                    "Got 402 after " + tripCount + " trips");
            } else {
                return new ScenarioResult("Insufficient Balance", false,
                    System.currentTimeMillis() - start,
                    "Expected 402 but got " + response.status());
            }

        } catch (Exception e) {
            if (driverWs != null) {
                driverWs.close();
            }
            return new ScenarioResult("Insufficient Balance", false,
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
