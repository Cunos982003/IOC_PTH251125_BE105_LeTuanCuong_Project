package com.ridehailing.dispatchservice;

import com.ridehailing.dispatchservice.client.UserClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class UserClientContractTest {
    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();
    private final MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();

    @Test
    void clientParsesContractResponse() throws Exception {
        var client = new UserClient(wiremock.baseUrl(), "test-key", converter);
        wiremock.stubFor(get("/internal/users/12345")
                .willReturn(ok(fixture("user-get-user.response"))
                        .withHeader("Content-Type", "application/json")));
        var user = client.getUser(12345L);
        assertThat(user.id()).isEqualTo(12345L);
        assertThat(user.role()).isEqualTo("DRIVER");
        assertThat(user.fullName()).isEqualTo("Nguyen Van A");
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/http/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
