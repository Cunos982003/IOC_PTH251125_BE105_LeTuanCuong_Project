package com.ridehailing.dispatchservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Component
public class PricingClient {

    private final RestClient restClient;

    public PricingClient(@Value("${PRICING_SERVICE_URL}") String baseUrl,
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

    public QuoteResponse getQuote(double pickupLat, double pickupLng,
                                   double dropoffLat, double dropoffLng) {
        QuoteRequest request = new QuoteRequest(pickupLat, pickupLng, dropoffLat, dropoffLng);
        return restClient.post()
            .uri("/internal/quote")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                throw new RuntimeException("Pricing service error: " + res.getStatusCode());
            })
            .body(QuoteResponse.class);
    }

    public record QuoteRequest(double pickupLat, double pickupLng,
                               double dropoffLat, double dropoffLng) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuoteResponse(long distanceM, long fare, BigDecimal surge) {}
}
