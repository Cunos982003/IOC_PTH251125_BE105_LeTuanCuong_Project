package com.ridehailing.paymentservice.service;

import com.ridehailing.paymentservice.repository.LedgerRepository;
import com.ridehailing.paymentservice.repository.PaymentFailureRepository;
import com.ridehailing.paymentservice.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.UUID;

@Service
public class SettlementService {

    private static final long PLATFORM_USER_ID = 0L;

    private final WalletRepository walletRepository;
    private final LedgerRepository ledgerRepository;
    private final PaymentFailureRepository paymentFailureRepository;
    private final int commissionRate;

    public SettlementService(
            WalletRepository walletRepository,
            LedgerRepository ledgerRepository,
            PaymentFailureRepository paymentFailureRepository,
            @Value("${payment.commission-rate:20}") int commissionRate) {
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.paymentFailureRepository = paymentFailureRepository;
        this.commissionRate = commissionRate;
    }

    @Transactional
    public void settle(UUID tripId, long customerId, long driverId, long fare) {
        try {
            // Step 1: Insert FARE ledger entry (idempotency check)
            int inserted = ledgerRepository.insertEntry(tripId, customerId, -fare, "FARE");
            if (inserted == 0) {
                // Already processed, return early
                return;
            }

            // Step 2: Calculate commission and payout
            long commission = (fare * commissionRate) / 100;
            long payout = fare - commission;

            // Step 3: Insert PAYOUT and COMMISSION ledger entries
            ledgerRepository.insertEntry(tripId, driverId, payout, "PAYOUT");
            ledgerRepository.insertEntry(tripId, PLATFORM_USER_ID, commission, "COMMISSION");

            // Step 4: Lock wallets in ascending order and update balances
            // customer: -fare, driver: +payout, platform: +commission
            walletRepository.lockAndUpdateBalances(
                    Arrays.asList(customerId, driverId, PLATFORM_USER_ID),
                    Arrays.asList(-fare, payout, commission)
            );

        } catch (DataIntegrityViolationException e) {
            // CHECK constraint violation (balance < 0)
            String reason = "Insufficient balance: " + e.getMessage();
            paymentFailureRepository.recordFailure(tripId, reason);
            throw new IllegalStateException(reason, e);
        }
    }
}
