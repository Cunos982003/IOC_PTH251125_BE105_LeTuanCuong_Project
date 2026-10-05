package com.ridehailing.dispatchservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class WsGatewayClient {

    private final RestClient restClient;

    public WsGatewayClient(@Value("${WS_GATEWAY_URL}") String baseUrl,
                           @Value("${INTERNAL_KEY}") String internalKey,
                           MappingJackson2HttpMessageConverter jacksonConverter) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(5000);

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("X-Internal-Key", internalKey)
            .defaultHeader("Accept", "application/json")
            .requestFactory(requestFactory)
            .messageConverters(converters -> converters.add(jacksonConverter))
            .build();
    }

    public void notifyDriverOffer(long driverId, UUID tripId, long fare) {
        NotifyRequest request = new NotifyRequest("driver_offer",
            new DriverOfferPayload(tripId.toString(), fare));
        restClient.post()
            .uri("/internal/notify/{driverId}", driverId)
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                System.err.println("WS notification failed: " + res.getStatusCode());
            })
            .toBodilessEntity();
    }

    public void notifyTripUpdate(long customerId, UUID tripId, String status) {
        NotifyRequest request = new NotifyRequest("trip_update",
            new TripUpdatePayload(tripId.toString(), status));
        restClient.post()
            .uri("/internal/notify/{customerId}", customerId)
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                System.err.println("WS notification failed: " + res.getStatusCode());
            })
            .toBodilessEntity();
    }

    public void notifyTripCreated(long customerId, UUID tripId) {
        NotifyRequest request = new NotifyRequest("trip_created",
            new TripCreatedPayload(tripId.toString()));
        restClient.post()
            .uri("/internal/notify/{customerId}", customerId)
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                System.err.println("WS notification failed: " + res.getStatusCode());
            })
            .toBodilessEntity();
    }

    public void deleteRoute(long driverId) {
        restClient.delete()
            .uri("/internal/routes/{driverId}", driverId)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                System.err.println("Failed to delete route: " + res.getStatusCode());
            })
            .toBodilessEntity();
    }

    public record NotifyRequest(String type, Object payload) {}
    public record TripCreatedPayload(String tripId) {}
    public record DriverOfferPayload(String tripId, long fare) {}
    public record TripUpdatePayload(String tripId, String status) {}
}
