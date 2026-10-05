package com.ridehailing.userservice.repository;

import com.ridehailing.userservice.model.User;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcClient jdbcClient;

    public UserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<User> findByEmail(String email) {
        return jdbcClient.sql("SELECT id, email, password_hash, role, full_name, phone, created_at FROM users WHERE email = ?")
                .param(email)
                .query(User.class)
                .optional();
    }

    public Optional<User> findById(Long id) {
        return jdbcClient.sql("SELECT id, email, password_hash, role, full_name, phone, created_at FROM users WHERE id = ?")
                .param(id)
                .query(User.class)
                .optional();
    }

    public Long insert(User user, String passwordHash) {
        return jdbcClient.sql("""
                        INSERT INTO users (email, password_hash, role, full_name, phone)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .params(user.email(), passwordHash, user.role(), user.fullName(), user.phone())
                .query(Long.class)
                .single();
    }
}