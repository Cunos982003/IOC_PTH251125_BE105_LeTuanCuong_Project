package com.ridehailing.e2e.model;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
@JsonIgnoreProperties(ignoreUnknown = true)
public record TripResponse(UUID tripId, String status, Long fare) {}
