package com.ridehailing.dispatchservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Component
public class LocationClient {

    private final RestClient restClient;

    public LocationClient(@Value("${LOCATION_SERVICE_URL}") String baseUrl,
                          @Value("${INTERNAL_KEY}") String internalKey,
                          MappingJackson2HttpMessageConverter jacksonConverter) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(500);
        requestFactory.setReadTimeout(500);

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("X-Internal-Key", internalKey)
            .defaultHeader("X-Caller-Service", "dispatch-service")
            .defaultHeader("Accept", "application/json")
            .requestFactory(requestFactory)
            .messageConverters(converters -> converters.add(jacksonConverter))
            .build();
    }

    public List<DriverLocation> nearby(double lat, double lng, int radiusM, int limit) {
        DriverLocation[] response = restClient.get()
            .uri("/internal/drivers/nearby?lat={lat}&lng={lng}&radiusM={radius}&limit={limit}",
                 lat, lng, radiusM, limit)
            .retrieve()
            .body(DriverLocation[].class);

        return response != null ? List.of(response) : List.of();
    }

    public StatusResponse busy(long driverId, UUID tripId) {
        return restClient.post()
            .uri("/internal/drivers/{driverId}/busy", driverId)
            .body(new BusyRequest(tripId.toString()))
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                System.err.println("Failed to mark driver busy: " + res.getStatusCode());
            })
            .body(StatusResponse.class);
    }

    public StatusResponse free(long driverId) {
        return restClient.post()
            .uri("/internal/drivers/{driverId}/free", driverId)
            .body(java.util.Map.of())
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                System.err.println("Failed to mark driver free: " + res.getStatusCode());
            })
            .body(StatusResponse.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NearbyDriversResponse(List<DriverLocation> drivers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriverLocation(long driverId, double lat, double lng, double distanceM) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusResponse(String status) {}

    public record BusyRequest(String tripId) {}
}
