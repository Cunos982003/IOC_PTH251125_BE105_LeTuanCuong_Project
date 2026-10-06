package com.ridehailing.pricingservice;

import com.ridehailing.pricingservice.service.LocationClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class LocationClientContractTest {
    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();
    private final MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();

    @Test
    void clientParsesContractResponses() throws Exception {
        var client = new LocationClient(wiremock.baseUrl(), 2000, "test-key");
        wiremock.stubFor(get("/internal/drivers/count?lat=10.7769&lng=106.7009&radiusM=2000")
                .willReturn(ok(fixture("location-get-count.response")).withHeader("Content-Type", "application/json")));
        int count = client.getDriverCount(10.7769, 106.7009, 2.0);
        assertThat(count).isEqualTo(15);
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/http/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}