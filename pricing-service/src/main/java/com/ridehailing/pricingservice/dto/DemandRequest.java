package com.ridehailing.pricingservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DemandRequest(String tripId, double lat, double lng) {}
