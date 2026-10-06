package com.ridehailing.pricingservice.service;

import com.ridehailing.pricingservice.dto.DriverCountResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Service
public class LocationClient {
    private static final Logger log = LoggerFactory.getLogger(LocationClient.class);

    private final RestClient restClient;
    private final String internalKey;

    public LocationClient(@Value("${location-service.url}") String baseUrl,
                          @Value("${location-service.timeout-ms}") long timeoutMs,
                          @Value("${internal.key}") String internalKey) {
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .build();
        this.internalKey = internalKey;
    }

    public int getDriverCount(double lat, double lng, double radiusKm) {
        try {
            DriverCountResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/internal/drivers/count")
                    .queryParam("lat", lat)
                    .queryParam("lng", lng)
                    .queryParam("radiusM", Math.round(radiusKm * 1000))
                    .build())
                .header("X-Internal-Key", internalKey)
                .retrieve()
                .body(DriverCountResponse.class);

            return response != null ? response.count() : 0;
        } catch (Exception e) {
            log.warn("Failed to get driver count from location-service: {}", e.getMessage());
            return 0;
        }
    }
}
