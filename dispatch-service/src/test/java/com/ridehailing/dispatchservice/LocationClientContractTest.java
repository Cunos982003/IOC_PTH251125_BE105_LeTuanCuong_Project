package com.ridehailing.dispatchservice;

import com.ridehailing.dispatchservice.client.LocationClient;
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
        var client = new LocationClient(wiremock.baseUrl(), "test-key", converter);
        wiremock.stubFor(get("/internal/drivers/nearby?lat=10.7769&lng=106.7009&radiusM=2000&limit=10")
                .willReturn(ok(fixture("location-get-nearby.response")).withHeader("Content-Type", "application/json")));
        var nearby = client.nearby(10.7769, 106.7009, 2000, 10);
        assertThat(nearby).hasSize(2);
        assertThat(nearby.get(0).driverId()).isEqualTo(67890L);
        assertThat(nearby.get(0).distanceM()).isEqualTo(150.0);
        wiremock.stubFor(post("/internal/drivers/67890/busy").willReturn(ok(fixture("location-post-busy.response")).withHeader("Content-Type", "application/json")));
        var busy = client.busy(67890L, java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(busy.status()).isEqualTo("BUSY");
        wiremock.stubFor(post("/internal/drivers/67890/free").willReturn(ok(fixture("location-post-free.response")).withHeader("Content-Type", "application/json")));
        var free = client.free(67890L);
        assertThat(free.status()).isEqualTo("ONLINE");
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/http/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}