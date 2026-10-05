package com.ridehailing.pricingservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record QuoteRequest(Location pickup, Location dropoff) {
    public record Location(double lat, double lng) {}
}
