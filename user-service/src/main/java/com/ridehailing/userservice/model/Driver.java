package com.ridehailing.userservice.model;

public record Driver(
        Long userId,
        String licenseNo,
        String status
) {
}