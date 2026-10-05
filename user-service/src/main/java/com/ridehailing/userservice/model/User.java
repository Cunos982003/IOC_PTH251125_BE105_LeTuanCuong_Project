package com.ridehailing.userservice.model;

import java.time.Instant;

public record User(
        Long id,
        String email,
        String passwordHash,
        String role,
        String fullName,
        String phone,
        Instant createdAt
) {
}