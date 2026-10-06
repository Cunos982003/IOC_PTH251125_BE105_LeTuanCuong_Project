package com.ridehailing.e2e.model;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterResponse(String accessToken) {}
