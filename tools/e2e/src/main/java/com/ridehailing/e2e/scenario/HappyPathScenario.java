package com.ridehailing.e2e.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehailing.e2e.client.ApiGatewayClient;
import com.ridehailing.e2e.client.RedisTestClient;
import com.ridehailing.e2e.client.WsGatewayClient;
import com.ridehailing.e2e.model.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class HappyPathScenario {
    private final ApiGatewayClient apiClient;
    private final String wsUrl;
    private final RedisTestClient redisClient;

    public HappyPathScenario(ApiGatewayClient apiClient, String wsUrl, RedisTestClient redisClient) {
        this.apiClient = apiClient;
        this.wsUrl = wsUrl;
        this.redisClient = redisClient;
    }

    public ScenarioResult run() {
        long start = System.currentTimeMillis();
        try {
            String riderEmail = "rider-" + UUID.randomUUID() + "@test.com";
            String driverEmail = "driver-" + UUID.randomUUID() + "@test.com";
            String password = "password123";

            // 1. Đăng ký rider và driver
            RegisterResponse rider = apiClient.register(new RegisterRequest(
                riderEmail, password, "Test Rider", "+84901000001", "rider"
            ));
            RegisterResponse driver = apiClient.register(new RegisterRequest(
                driverEmail, password, "Test Driver", "+84901000002", "driver"
            ));

            // 2. Đăng nhập
            LoginResponse riderLogin = apiClient.login(new LoginRequest(riderEmail, password));
            LoginResponse driverLogin = apiClient.login(new LoginRequest(driverEmail, password));

            // 3. Chờ ví được tạo (tối đa 5 giây)
            WalletResponse riderWallet = waitForWallet(riderLogin.token(), 5000);
            WalletResponse driverWallet = waitForWallet(driverLogin.token(), 5000);

            if (riderWallet == null || driverWallet == null) {
                return new ScenarioResult("Happy Path", false,
                    System.currentTimeMillis() - start, "Wallet creation timeout");
            }

            long initialRiderBalance = riderWallet.balance();
            long initialDriverBalance = driverWallet.balance();

            // 4. Driver nối WebSocket và gửi vị trí
            WsGatewayClient driverWs = new WsGatewayClient(wsUrl);
            driverWs.connect(driverLogin.token());
            Thread.sleep(500);

            double driverLat = 21.0285;
            double driverLng = 105.8542;
            driverWs.sendLocation(driverLat, driverLng);
            Thread.sleep(500);

            // 5. Rider đặt xe
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

            // 6. Driver nhận offer và accept
            if (!offerLatch.await(10, TimeUnit.SECONDS)) {
                driverWs.close();
                return new ScenarioResult("Happy Path", false,
                    System.currentTimeMillis() - start, "No trip offer received");
            }

            Long tripId = tripIdRef.get();
            driverWs.acceptOffer(tripId);
            Thread.sleep(1000);

            // 7. Driver arrive/start/complete
            driverWs.arrive(tripId);
            Thread.sleep(500);
            driverWs.startTrip(tripId);
            Thread.sleep(500);
            driverWs.completeTrip(tripId);
            Thread.sleep(2000); // Chờ payment xử lý

            // 8. Kiểm tra ví: rider giảm fare, driver tăng 80% fare
            WalletResponse riderWalletAfter = apiClient.getWallet(riderLogin.token());
            WalletResponse driverWalletAfter = apiClient.getWallet(driverLogin.token());

            long fare = trip.fare();
            long expectedDriverEarning = (fare * 80) / 100;

            if (riderWalletAfter.balance() != initialRiderBalance - fare) {
                driverWs.close();
                return new ScenarioResult("Happy Path", false,
                    System.currentTimeMillis() - start,
                    String.format("Rider balance mismatch: expected %d, got %d",
                        initialRiderBalance - fare, riderWalletAfter.balance()));
            }

            if (driverWalletAfter.balance() != initialDriverBalance + expectedDriverEarning) {
                driverWs.close();
                return new ScenarioResult("Happy Path", false,
                    System.currentTimeMillis() - start,
                    String.format("Driver balance mismatch: expected %d, got %d",
                        initialDriverBalance + expectedDriverEarning, driverWalletAfter.balance()));
            }

            // 9. Phát lại TripCompleted thủ công
            redisClient.publishTripCompletedEvent(tripId, rider.userId(), driver.userId(), fare);
            Thread.sleep(2000);

            // 10. Kiểm tra số dư không đổi (idempotent)
            WalletResponse riderWalletFinal = apiClient.getWallet(riderLogin.token());
            WalletResponse driverWalletFinal = apiClient.getWallet(driverLogin.token());

            if (riderWalletFinal.balance() != riderWalletAfter.balance()) {
                driverWs.close();
                return new ScenarioResult("Happy Path", false,
                    System.currentTimeMillis() - start,
                    "Rider balance changed after replay (not idempotent)");
            }

            if (driverWalletFinal.balance() != driverWalletAfter.balance()) {
                driverWs.close();
                return new ScenarioResult("Happy Path", false,
                    System.currentTimeMillis() - start,
                    "Driver balance changed after replay (not idempotent)");
            }

            driverWs.close();
            return new ScenarioResult("Happy Path", true,
                System.currentTimeMillis() - start, "OK");

        } catch (Exception e) {
            return new ScenarioResult("Happy Path", false,
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
