package com.ridehailing.e2e.model;
public record TripRequest(double pickupLat, double pickupLng, double dropoffLat, double dropoffLng, String idempotencyKey) {
    public TripRequest(double pickupLat, double pickupLng, double dropoffLat, double dropoffLng) {
        this(pickupLat, pickupLng, dropoffLat, dropoffLng, null);
    }
}
