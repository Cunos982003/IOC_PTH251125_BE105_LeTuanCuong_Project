package com.ridehailing.wsgateway.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
public class LocationServiceClient {
    private final HttpClient client;
    private final String baseUrl;
    private final String internalKey;

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
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(new BatchRequest(locations));

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BatchRequest(@JsonProperty("locations") List<LocationDto> locations) {
    }
}
