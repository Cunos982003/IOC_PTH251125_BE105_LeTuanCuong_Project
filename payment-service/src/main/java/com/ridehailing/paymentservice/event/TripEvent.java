package com.ridehailing.paymentservice.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TripEvent(
        String eventType,
        UUID tripId,
        Long customerId,
        Long driverId,
        Long fare
) {}
