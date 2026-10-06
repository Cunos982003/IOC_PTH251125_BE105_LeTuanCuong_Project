package com.ridehailing.e2e.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletResponse(
    @JsonProperty("userId") Long userId,
    @JsonProperty("balance") Long balance
) {}
