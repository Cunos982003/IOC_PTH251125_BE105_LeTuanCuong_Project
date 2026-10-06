package com.ridehailing.dispatchservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Component
public class WsGatewayClient {
    private final RestClient restClient;

    public WsGatewayClient(@Value("${WS_GATEWAY_URL}") String baseUrl,
                           @Value("${INTERNAL_KEY}") String internalKey,
                           MappingJackson2HttpMessageConverter jacksonConverter) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        restClient = RestClient.builder().baseUrl(baseUrl)
                .defaultHeader("X-Internal-Key", internalKey).requestFactory(factory)
                .messageConverters(converters -> converters.add(jacksonConverter)).build();
    }

    public PushResponse push(long userId, String type, Object payload) {
        return restClient.post().uri("/internal/push").body(new PushRequest(userId, type, payload))
                .retrieve().body(PushResponse.class);
    }

    public void notifyDriverOffer(long driverId, UUID tripId, long fare) {
        push(driverId, "TRIP_REQUEST", Map.of("tripId", tripId.toString(), "fare", fare));
    }

    public void notifyTripUpdate(long customerId, UUID tripId, String status) {
        push(customerId, "NOTIFICATION", Map.of("tripId", tripId.toString(), "status", status));
    }

    public void notifyTripCreated(long customerId, UUID tripId) {
        push(customerId, "NOTIFICATION", Map.of("tripId", tripId.toString()));
    }

    public RouteResponse setRoute(long driverId, long customerId) {
        return restClient.put().uri("/internal/routes/{driverId}", driverId)
                .body(Map.of("customerId", customerId)).retrieve().body(RouteResponse.class);
    }

    public DeleteResponse deleteRoute(long driverId) {
        return restClient.delete().uri("/internal/routes/{driverId}", driverId)
                .retrieve().body(DeleteResponse.class);
    }

    public record PushRequest(long userId, String type, Object payload) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PushResponse(boolean delivered) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteResponse(boolean mapped) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeleteResponse(boolean deleted) {}
}
