package com.ridehailing.pricingservice.service;

import com.ridehailing.pricingservice.config.PricingConfig;
import com.ridehailing.pricingservice.dto.QuoteRequest;
import com.ridehailing.pricingservice.dto.QuoteResponse;
import org.springframework.stereotype.Service;

@Service
public class PricingService {

    private final PricingConfig config;
    private final DemandService demandService;

    public PricingService(PricingConfig config, DemandService demandService) {
        this.config = config;
        this.demandService = demandService;
    }

    public QuoteResponse calculateQuote(QuoteRequest request) {
        double lat = request.pickup().lat();
        double lng = request.pickup().lng();

        long distanceM = calculateDistance(
            request.pickup().lat(), request.pickup().lng(),
            request.dropoff().lat(), request.dropoff().lng()
        );

        long durationS = calculateDuration(distanceM);
        double surge = demandService.calculateSurge(lat, lng);
        long fare = calculateFare(distanceM, durationS, surge);

        return new QuoteResponse(distanceM, durationS, surge, fare);
    }

    private long calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        double haversine = haversineMeters(lat1, lng1, lat2, lng2);
        return Math.round(haversine * config.getDistanceMultiplier());
    }

    private long calculateDuration(long distanceM) {
        double distanceKm = distanceM / 1000.0;
        double hours = distanceKm / config.getAvgSpeedKmh();
        return Math.round(hours * 3600);
    }

    private long calculateFare(long distanceM, long durationS, double surge) {
        double km = distanceM / 1000.0;
        double minutes = durationS / 60.0;

        long baseFare = config.getBaseFare() +
                        Math.round(km * config.getPerKm()) +
                        Math.round(minutes * config.getPerMinute());

        long fareBeforeSurge = Math.max(config.getMinFare(), baseFare);
        long fareWithSurge = Math.round(fareBeforeSurge * surge);

        // Làm tròn LÊN bội số 1000
        return ((fareWithSurge + 999) / 1000) * 1000;
    }

    private double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLng / 2) * Math.sin(dLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
