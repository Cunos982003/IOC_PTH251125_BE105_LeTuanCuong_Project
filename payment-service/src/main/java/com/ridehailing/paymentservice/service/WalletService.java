package com.ridehailing.paymentservice.service;

import com.ridehailing.paymentservice.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    public long getBalance(long userId) {
        return walletRepository.findBalance(userId)
                .orElseGet(() -> {
                    createWallet(userId, 0L);
                    return 0L;
                });
    }

    @Transactional
    public void createWallet(long userId, long initialBalance) {
        if (initialBalance < 0) {
            throw new IllegalArgumentException("Initial balance cannot be negative");
        }
        walletRepository.createWallet(userId, initialBalance);
    }

    @Transactional
    public void topup(long userId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Topup amount must be positive");
        }
        if (amount > 10_000_000) {
            throw new IllegalArgumentException("Topup amount cannot exceed 10,000,000");
        }

        // Check wallet exists
        walletRepository.findBalance(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user " + userId));

        walletRepository.topup(userId, amount);
    }
}
