package com.ridehailing.e2e.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorResponse(
    @JsonProperty("code") String code,
    @JsonProperty("message") String message
) {}
