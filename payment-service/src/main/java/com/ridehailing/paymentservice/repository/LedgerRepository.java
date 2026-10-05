package com.ridehailing.paymentservice.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public class LedgerRepository {

    private final JdbcClient jdbcClient;

    public LedgerRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public int insertEntry(UUID tripId, long userId, long amount, String type) {
        return jdbcClient.sql("""
                INSERT INTO ledger_entries (trip_id, user_id, amount, type, created_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (trip_id, user_id, type) DO NOTHING
                """)
                .param(tripId)
                .param(userId)
                .param(amount)
                .param(type)
                .update();
    }

    public long sumEntriesByType(String type) {
        Long sum = jdbcClient.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE type = ?")
                .param(type)
                .query(Long.class)
                .single();
        return sum != null ? sum : 0L;
    }

    public long countEntries(UUID tripId, String type) {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM ledger_entries WHERE trip_id = ? AND type = ?")
                .param(tripId)
                .param(type)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }
}
