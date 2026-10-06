package com.ridehailing.e2e.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterResponse(
    @JsonProperty("userId") Long userId,
    @JsonProperty("email") String email,
    @JsonProperty("name") String name,
    @JsonProperty("role") String role
) {}