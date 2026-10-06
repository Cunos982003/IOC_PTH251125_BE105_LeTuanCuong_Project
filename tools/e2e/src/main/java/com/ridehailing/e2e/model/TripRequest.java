package com.ridehailing.e2e.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TripRequest(
    @JsonProperty("pickupLat") double pickupLat,
    @JsonProperty("pickupLng") double pickupLng,
    @JsonProperty("dropoffLat") double dropoffLat,
    @JsonProperty("dropoffLng") double dropoffLng
) {}
