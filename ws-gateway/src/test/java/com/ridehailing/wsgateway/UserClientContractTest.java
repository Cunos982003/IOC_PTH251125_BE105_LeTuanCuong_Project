package com.ridehailing.wsgateway;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class UserClientContractTest {
    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @Test
    void clientParsesContractResponse() throws Exception {
        wiremock.stubFor(get("/internal/users/12345")
                .willReturn(ok(fixture("user-get-user.response"))
                        .withHeader("Content-Type", "application/json")));

        var client = RestClient.builder().baseUrl(wiremock.baseUrl()).build();
        var response = client.get()
                .uri("/internal/users/12345")
                .header("X-Internal-Key", "test-key")
                .retrieve()
                .body(UserResponse.class);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(12345L);
        assertThat(response.role()).isEqualTo("DRIVER");
        assertThat(response.fullName()).isEqualTo("Nguyen Van A");
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/http/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    record UserResponse(long id, String role, String fullName) {}
}