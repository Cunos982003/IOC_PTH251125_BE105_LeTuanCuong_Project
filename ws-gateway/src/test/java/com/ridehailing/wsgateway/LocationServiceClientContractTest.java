package com.ridehailing.wsgateway;

import com.ridehailing.wsgateway.client.LocationServiceClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThatCode;

class LocationServiceClientContractTest {
    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @Test
    void clientParsesContractResponse() throws Exception {
        var client = new LocationServiceClient(wiremock.baseUrl(), "test-key");
        wiremock.stubFor(post("/internal/locations")
                .willReturn(ok(fixture("location-post-locations.response"))
                        .withHeader("Content-Type", "application/json")));

        assertThatCode(() -> client.batchUpdate(java.util.List.of(
                new LocationServiceClient.LocationDto("67890", 10.7769, 106.7009,
                        java.time.Instant.parse("2026-10-04T10:30:00Z").toEpochMilli()))))
                .doesNotThrowAnyException();
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/http/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
