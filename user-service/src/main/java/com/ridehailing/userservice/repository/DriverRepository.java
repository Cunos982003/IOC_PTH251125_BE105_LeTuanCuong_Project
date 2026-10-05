package com.ridehailing.userservice.repository;

import com.ridehailing.userservice.model.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class DriverRepository {

    private final JdbcClient jdbcClient;

    public DriverRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Driver> findByUserId(Long userId) {
        return jdbcClient.sql("SELECT user_id, license_no, status FROM drivers WHERE user_id = ?")
                .param(userId)
                .query(Driver.class)
                .optional();
    }

    public void insert(Long userId) {
        jdbcClient.sql("INSERT INTO drivers (user_id, status) VALUES (?, 'OFFLINE')")
                .param(userId)
                .update();
    }

    public int updateStatus(Long userId, String status) {
        return jdbcClient.sql("UPDATE drivers SET status = ? WHERE user_id = ?")
                .params(status, userId)
                .update();
    }
}