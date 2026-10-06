package com.ridehailing.e2e.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TripResponse(
    @JsonProperty("tripId") Long tripId,
    @JsonProperty("status") String status,
    @JsonProperty("fare") Long fare
) {}
