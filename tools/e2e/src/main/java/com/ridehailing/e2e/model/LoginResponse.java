package com.ridehailing.e2e.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginResponse(
    @JsonProperty("token") String token,
    @JsonProperty("userId") Long userId,
    @JsonProperty("role") String role
) {}
