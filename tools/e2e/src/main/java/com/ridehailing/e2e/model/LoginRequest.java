package com.ridehailing.e2e.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginRequest(
    @JsonProperty("email") String email,
    @JsonProperty("password") String password
) {}
