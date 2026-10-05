package com.ridehailing.userservice.model;

public record Vehicle(
        Long id,
        Long driverId,
        String plate,
        String type,
        String model
) {
}