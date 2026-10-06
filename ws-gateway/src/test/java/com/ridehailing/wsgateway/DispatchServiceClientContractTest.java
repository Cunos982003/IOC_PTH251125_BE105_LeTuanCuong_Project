package com.ridehailing.wsgateway;

import com.ridehailing.wsgateway.client.DispatchServiceClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class DispatchServiceClientContractTest {
    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();
    private final MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();

    @Test
    void clientParsesContractResponses() throws Exception {
        var client = new DispatchServiceClient(wiremock.baseUrl(), "test-key");
        wiremock.stubFor(post("/internal/trips/550e8400-e29b-41d4-a716-446655440000/accept")
                .willReturn(ok(fixture("dispatch-post-accept.response")).withHeader("Content-Type", "application/json")));
        client.acceptTrip("550e8400-e29b-41d4-a716-446655440000", "67890");
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/http/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}