package com.ridehailing.dispatchservice;

import com.ridehailing.dispatchservice.client.PricingClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class PricingClientContractTest {
    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();
    private final MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();

    @Test
    void clientParsesContractResponses() throws Exception {
        var client = new PricingClient(wiremock.baseUrl(), "test-key", converter);
        wiremock.stubFor(post("/internal/quote").willReturn(ok(fixture("pricing-post-quote.response")).withHeader("Content-Type", "application/json")));
        var quote = client.getQuote(10.7769, 106.7009, 10.8231, 106.6297);
        assertThat(quote.fare()).isEqualTo(45000L);
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/http/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}