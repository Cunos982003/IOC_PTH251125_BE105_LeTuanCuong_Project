package com.ridehailing.paymentservice.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserRegisteredEvent(
        Long userId,
        String role
) {}
