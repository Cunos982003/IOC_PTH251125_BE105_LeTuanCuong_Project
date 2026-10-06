package com.ridehailing.e2e.model;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginResponse(@JsonProperty("accessToken") String token) {}
