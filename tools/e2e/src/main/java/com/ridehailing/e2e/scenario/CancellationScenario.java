package com.ridehailing.e2e.scenario;

import com.ridehailing.e2e.client.ApiGatewayClient;
import com.ridehailing.e2e.client.WsGatewayClient;
import com.ridehailing.e2e.model.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CancellationScenario {
    private final ApiGatewayClient apiClient;
    private final String wsUrl;

    public CancellationScenario(ApiGatewayClient apiClient, String wsUrl) {
        this.apiClient = apiClient;
        this.wsUrl = wsUrl;
    }

    public ScenarioResult run() {
        long start = System.currentTimeMillis();
        try {
            String riderEmail = "rider-cancel-" + UUID.randomUUID() + "@test.com";
            String driverEmail = "driver-cancel-" + UUID.randomUUID() + "@test.com";
            String password = "password123";

            // 1. Đăng ký và đăng nhập
            apiClient.register(new RegisterRequest(
                riderEmail, password, "Test Rider Cancel", "+84901000020", "rider"
            ));
            apiClient.register(new RegisterRequest(
                driverEmail, password, "Test Driver Cancel", "+84901000021", "driver"
            ));

            LoginResponse riderLogin = apiClient.login(new LoginRequest(riderEmail, password));
            LoginResponse driverLogin = apiClient.login(new LoginRequest(driverEmail, password));

            // 2. Chờ ví
            WalletResponse riderWallet = waitForWallet(riderLogin.token(), 5000);
            if (riderWallet == null) {
                return new ScenarioResult("Cancellation", false,
                    System.currentTimeMillis() - start, "Wallet creation timeout");
            }

            // 3. Driver nối WebSocket và gửi vị trí
            WsGatewayClient driverWs = new WsGatewayClient(wsUrl);
            driverWs.connect(driverLogin.token());
            Thread.sleep(500);

            double driverLat = 21.0285;
            double driverLng = 105.8542;
            driverWs.sendLocation(driverLat, driverLng);
            Thread.sleep(500);

            // 4. Rider đặt xe
            CountDownLatch offerLatch = new CountDownLatch(1);
            AtomicReference<Long> tripIdRef = new AtomicReference<>();

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

            // 5. Driver nhận offer và accept
            if (!offerLatch.await(10, TimeUnit.SECONDS)) {
                driverWs.close();
                return new ScenarioResult("Cancellation", false,
                    System.currentTimeMillis() - start, "No trip offer received");
            }

            Long tripId = tripIdRef.get();
            driverWs.acceptOffer(tripId);
            Thread.sleep(1000);

            // 6. Driver arrive (đang đến)
            driverWs.arrive(tripId);
            Thread.sleep(500);

            // 7. Rider hủy chuyến
            apiClient.cancelTrip(riderLogin.token(), tripId);
            Thread.sleep(1000);

            // 8. Driver gửi vị trí lại → kiểm tra driver rảnh lại (xuất hiện trong nearby)
            driverWs.sendLocation(driverLat, driverLng);
            Thread.sleep(500);

            // 9. Thử đặt xe mới để kiểm tra driver có sẵn sàng không
            String idempotencyKey2 = UUID.randomUUID().toString();
            CountDownLatch offerLatch2 = new CountDownLatch(1);
            AtomicReference<Long> tripId2Ref = new AtomicReference<>();

            driverWs.onMessage("trip_offer", json -> {
                tripId2Ref.set(json.get("tripId").asLong());
                offerLatch2.countDown();
            });

            TripResponse trip2 = apiClient.requestTrip(
                riderLogin.token(),
                new TripRequest(21.0285, 105.8542, 21.0368, 105.8345),
                idempotencyKey2
            );

            if (!offerLatch2.await(10, TimeUnit.SECONDS)) {
                driverWs.close();
                return new ScenarioResult("Cancellation", false,
                    System.currentTimeMillis() - start,
                    "Driver did not become available again after cancellation");
            }

            driverWs.close();
            return new ScenarioResult("Cancellation", true,
                System.currentTimeMillis() - start,
                "Driver became available again after cancellation");

        } catch (Exception e) {
            return new ScenarioResult("Cancellation", false,
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
