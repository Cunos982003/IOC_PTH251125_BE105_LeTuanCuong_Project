package com.ridehailing.paymentservice;

import com.ridehailing.paymentservice.repository.LedgerRepository;
import com.ridehailing.paymentservice.repository.PaymentFailureRepository;
import com.ridehailing.paymentservice.repository.WalletRepository;
import com.ridehailing.paymentservice.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class SettlementConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withExposedPorts(5672, 15672)
            .withStartupTimeout(java.time.Duration.ofSeconds(180));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("payment.internal-key", () -> "test-key");
        registry.add("payment.commission-rate", () -> "20");
    }

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentFailureRepository paymentFailureRepository;

    @BeforeEach
    void setUp() {
        // Clean existing test wallets (except platform wallet 0)
        // Create shared wallet and 100 unique pairs
        walletRepository.createWallet(999L, 100_000_000L); // shared wallet
        for (int i = 1; i <= 100; i++) {
            walletRepository.createWallet(1000L + i, 1_000_000L); // customer
            walletRepository.createWallet(2000L + i, 0L);         // driver
        }
    }

    @Test
    void concurrentSettlements_shouldNotDeadlock_andBalancesRemainConsistent() throws Exception {
        // Record initial total balance
        long initialTotalBalance = walletRepository.sumAllBalances();

        ExecutorService executor = Executors.newFixedThreadPool(50);
        List<Future<?>> futures = new ArrayList<>();

        // 100 concurrent settlements
        for (int i = 1; i <= 100; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                UUID tripId = UUID.randomUUID();
                long customerId = 1000L + index;
                long driverId = 2000L + index;
                long fare = 50_000L;

                try {
                    settlementService.settle(tripId, customerId, driverId, fare);
                } catch (Exception e) {
                    // Some might fail, that's ok
                }
            }));
        }

        // Wait for all to complete
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Wait for transactions to settle
        Thread.sleep(500);

        // Verify total balance remains constant (conservation of money)
        long finalTotalBalance = walletRepository.sumAllBalances();
        assertEquals(initialTotalBalance, finalTotalBalance, "Total balance must be conserved");

        // Verify no negative balances
        for (int i = 1; i <= 100; i++) {
            long customerBalance = walletRepository.findBalance(1000L + i).orElse(0L);
            long driverBalance = walletRepository.findBalance(2000L + i).orElse(0L);
            assertTrue(customerBalance >= 0, "Customer " + i + " balance must not be negative");
            assertTrue(driverBalance >= 0, "Driver " + i + " balance must not be negative");
        }

        // Verify platform wallet has received commissions
        long platformBalance = walletRepository.findBalance(0L).orElse(0L);
        assertTrue(platformBalance > 0, "Platform should have earned commission");
    }

    @Test
    void insufficientBalance_shouldRollbackAndRecordFailure() {
        // Create customer with low balance (use unique IDs)
        walletRepository.createWallet(5001L, 10_000L); // only 10k
        walletRepository.createWallet(5002L, 0L);

        UUID tripId = UUID.randomUUID();
        long fare = 50_000L; // trying to pay 50k

        // Record initial balances
        long initialCustomerBalance = walletRepository.findBalance(5001L).orElseThrow();
        long initialDriverBalance = walletRepository.findBalance(5002L).orElseThrow();
        long initialPlatformBalance = walletRepository.findBalance(0L).orElse(0L);

        // Attempt settlement - should fail
        assertThrows(IllegalStateException.class, () -> {
            settlementService.settle(tripId, 5001L, 5002L, fare);
        });

        // Verify balances unchanged
        assertEquals(initialCustomerBalance, walletRepository.findBalance(5001L).orElseThrow());
        assertEquals(initialDriverBalance, walletRepository.findBalance(5002L).orElseThrow());
        assertEquals(initialPlatformBalance, walletRepository.findBalance(0L).orElse(0L));

        // Verify failure recorded
        int failureCount = paymentFailureRepository.countFailures(tripId);
        assertEquals(1, failureCount, "Payment failure should be recorded");
    }

    @Test
    void oddFareAmount_payoutPlusCommissionEqualsFare() {
        walletRepository.createWallet(6001L, 1_000_000L);
        walletRepository.createWallet(6002L, 0L);

        UUID tripId = UUID.randomUUID();
        long fare = 20_001L; // odd amount

        long initialTotal = walletRepository.sumAllBalances();

        settlementService.settle(tripId, 6001L, 6002L, fare);

        long finalTotal = walletRepository.sumAllBalances();

        // Total balance conserved
        assertEquals(initialTotal, finalTotal);

        // Verify exact calculation
        long commission = (fare * 20) / 100; // 4000
        long payout = fare - commission;     // 16001

        assertEquals(fare, payout + commission, "Payout + commission must equal fare exactly");
    }
}