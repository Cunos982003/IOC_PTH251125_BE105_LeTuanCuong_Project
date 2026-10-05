package com.ridehailing.pricingservice.dto;

public record QuoteResponse(long distanceM, long durationS, double surge, long fare) {}
