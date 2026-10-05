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

@Component
public class DispatchServiceClient {
    private final HttpClient client;
    private final String baseUrl;
    private final String internalKey;

    public DispatchServiceClient(@Value("${services.dispatch}") String baseUrl,
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

    public void acceptTrip(String tripId, String driverId) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(new AcceptRequest(driverId));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/trips/" + tripId + "/accept"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Key", internalKey)
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new DispatchException(response.statusCode(), response.body());
            }
        } catch (DispatchException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to accept trip", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AcceptRequest(@JsonProperty("driverId") String driverId) {
    }

    public static class DispatchException extends RuntimeException {
        private final int statusCode;
        private final String body;

        public DispatchException(int statusCode, String body) {
            super("HTTP " + statusCode + ": " + body);
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }
}
