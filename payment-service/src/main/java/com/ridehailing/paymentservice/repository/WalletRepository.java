package com.ridehailing.paymentservice.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class WalletRepository {

    private final JdbcClient jdbcClient;

    public WalletRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Long> findBalance(long userId) {
        return jdbcClient.sql("SELECT balance FROM wallets WHERE user_id = ?")
                .param(userId)
                .query(Long.class)
                .optional();
    }

    @Transactional
    public void createWallet(long userId, long initialBalance) {
        jdbcClient.sql("INSERT INTO wallets (user_id, balance, updated_at) VALUES (?, ?, now()) ON CONFLICT (user_id) DO NOTHING")
                .param(userId)
                .param(initialBalance)
                .update();
    }

    @Transactional
    public void topup(long userId, long amount) {
        jdbcClient.sql("UPDATE wallets SET balance = balance + ?, updated_at = now() WHERE user_id = ?")
                .param(amount)
                .param(userId)
                .update();
    }

    // Lock wallets in ascending order to avoid deadlock
    @Transactional
    public void lockAndUpdateBalances(List<Long> userIds, List<Long> amounts) {
        if (userIds.size() != amounts.size()) {
            throw new IllegalArgumentException("userIds and amounts must have same size");
        }

        // Lock all wallets in sorted order FOR UPDATE
        List<Long> sortedIds = userIds.stream().sorted().distinct().toList();
        for (Long userId : sortedIds) {
            jdbcClient.sql("SELECT balance FROM wallets WHERE user_id = ? FOR UPDATE")
                    .param(userId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new IllegalStateException("Wallet not found: " + userId));
        }

        // Update balances
        for (int i = 0; i < userIds.size(); i++) {
            long userId = userIds.get(i);
            long amount = amounts.get(i);
            jdbcClient.sql("UPDATE wallets SET balance = balance + ?, updated_at = now() WHERE user_id = ?")
                    .param(amount)
                    .param(userId)
                    .update();
        }
    }

    public long sumAllBalances() {
        Long sum = jdbcClient.sql("SELECT COALESCE(SUM(balance), 0) FROM wallets")
                .query(Long.class)
                .single();
        return sum != null ? sum : 0L;
    }
}
