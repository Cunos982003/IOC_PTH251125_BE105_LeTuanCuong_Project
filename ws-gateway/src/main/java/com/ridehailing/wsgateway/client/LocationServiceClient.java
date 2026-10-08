package com.ridehailing.wsgateway.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class LocationServiceClient {
    private final HttpClient client;
    private final String baseUrl;
    private final String internalKey;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public LocationServiceClient(@Value("${services.location}") String baseUrl,
                                 @Value("${internal.key}") String internalKey) {
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalStateException("internal.key must be configured");
        }
        this.baseUrl = baseUrl;
        this.internalKey = internalKey;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
    }

    public void batchUpdate(List<LocationDto> locations) {
        try {
            List<LocationRequest> requests = locations.stream().map(location ->
                    new LocationRequest(
                            Long.parseLong(location.driverId()),
                            location.lat(),
                            location.lng(),
                            Instant.ofEpochMilli(location.sentAt()),
                            null
                    )).toList();
            String json = mapper.writeValueAsString(requests);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/locations"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Key", internalKey)
                    .timeout(Duration.ofSeconds(1))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new RuntimeException("HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to batch update locations", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocationDto(
            @JsonProperty("driverId") String driverId,
            @JsonProperty("lat") double lat,
            @JsonProperty("lng") double lng,
            @JsonProperty("sent_at") long sentAt
    ) {
    }

    // Internal request DTO matching the contract
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocationRequest(
            @JsonProperty("driverId") long driverId,
            @JsonProperty("lat") double lat,
            @JsonProperty("lng") double lng,
            @JsonProperty("sentAt") Instant sentAt,
            @JsonProperty("tripId") String tripId
    ) {
    }

}
