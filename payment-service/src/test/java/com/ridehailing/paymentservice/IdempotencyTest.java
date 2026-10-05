package com.ridehailing.paymentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import com.ridehailing.paymentservice.repository.LedgerRepository;
import com.ridehailing.paymentservice.repository.WalletRepository;
import com.ridehailing.paymentservice.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class IdempotencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--requirepass", "testpass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("payment.internal-key", () -> "test-key");
        registry.add("payment.commission-rate", () -> "20");
        registry.add("spring.data.redis.password", () -> "testpass");
    }

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // Create test wallets with unique IDs
        walletRepository.createWallet(7001L, 1_000_000L); // customer
        walletRepository.createWallet(7002L, 0L);         // driver
    }

    @Test
    void sameTripCompletedMultipleTimes_shouldOnlySettleOnce() throws Exception {
        UUID tripId = UUID.randomUUID();
        long customerId = 7001L;
        long driverId = 7002L;
        long fare = 100_000L;

        // Record initial balance
        long initialCustomerBalance = walletRepository.findBalance(customerId).orElse(0L);
        long initialDriverBalance = walletRepository.findBalance(driverId).orElse(0L);
        long initialPlatformBalance = walletRepository.findBalance(0L).orElse(0L);

        // Settle the same trip 5 times concurrently
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            futures.add(executor.submit(() -> {
                try {
                    settlementService.settle(tripId, customerId, driverId, fare);
                } catch (Exception e) {
                    // Ignore - might be idempotency skip
                }
            }));
        }

        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Wait a bit for all transactions to complete
        Thread.sleep(200);

        // Verify balances changed exactly once
        long finalCustomerBalance = walletRepository.findBalance(customerId).orElseThrow();
        long finalDriverBalance = walletRepository.findBalance(driverId).orElseThrow();
        long finalPlatformBalance = walletRepository.findBalance(0L).orElseThrow();

        long commission = (fare * 20) / 100;
        long payout = fare - commission;

        assertEquals(initialCustomerBalance - fare, finalCustomerBalance, "Customer balance should decrease by fare once");
        assertEquals(initialDriverBalance + payout, finalDriverBalance, "Driver balance should increase by payout once");
        assertEquals(initialPlatformBalance + commission, finalPlatformBalance, "Platform balance should increase by commission once");

        // Verify ledger has exactly 3 entries for this trip
        long fareEntries = countLedgerEntries(tripId, "FARE");
        long payoutEntries = countLedgerEntries(tripId, "PAYOUT");
        long commissionEntries = countLedgerEntries(tripId, "COMMISSION");

        assertEquals(1, fareEntries);
        assertEquals(1, payoutEntries);
        assertEquals(1, commissionEntries);
    }

    private long countLedgerEntries(UUID tripId, String type) {
        return ledgerRepository.countEntries(tripId, type);
    }
}
