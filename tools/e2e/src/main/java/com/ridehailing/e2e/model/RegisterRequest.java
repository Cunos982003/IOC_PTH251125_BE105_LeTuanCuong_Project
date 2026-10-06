package com.ridehailing.e2e.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RegisterRequest(
    @JsonProperty("email") String email,
    @JsonProperty("password") String password,
    @JsonProperty("name") String name,
    @JsonProperty("phone") String phone,
    @JsonProperty("role") String role
) {}