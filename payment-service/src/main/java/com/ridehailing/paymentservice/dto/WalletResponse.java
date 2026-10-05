package com.ridehailing.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletResponse(
        long userId,
        long balance
) {}
