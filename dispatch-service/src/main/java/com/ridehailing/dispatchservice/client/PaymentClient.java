package com.ridehailing.dispatchservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Value("${PAYMENT_SERVICE_URL}") String baseUrl,
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

    public BalanceResponse getBalance(long customerId) {
        return restClient.get()
            .uri("/internal/wallets/{customerId}/balance", customerId)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                throw new RuntimeException("Payment service error: " + res.getStatusCode());
            })
            .body(BalanceResponse.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BalanceResponse(long balance) {}
}
