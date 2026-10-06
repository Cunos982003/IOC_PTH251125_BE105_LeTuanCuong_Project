package com.ridehailing.paymentservice;

import com.ridehailing.paymentservice.controller.InternalWalletController;
import com.ridehailing.paymentservice.security.InternalKeyFilter;
import com.ridehailing.paymentservice.service.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = InternalHttpContractTest.Config.class, properties = {"INTERNAL_KEY=contract-key", "internal.key=contract-key", "payment.internal-key=contract-key"},
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class InternalHttpContractTest {
    @org.springframework.context.annotation.Configuration
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration", "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"})
    @Import({InternalWalletController.class, InternalKeyFilter.class})
    static class Config {
        @org.springframework.context.annotation.Bean
        org.springframework.security.web.SecurityFilterChain contractSecurity(org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }

        @org.springframework.context.annotation.Bean
        WalletService walletService() {
            return new WalletService(null) {
                @Override
                public long getBalance(long userId) {
                    if (userId == 12345L) return 2500000L;
                    return 0L;
                }
            };
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void responsesMatchContractSamples() throws Exception {
        check(get("/internal/wallets/12345/balance"), "payment-get-balance");
    }

    private String sample(String name, String suffix) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/http/" + name + suffix + ".json")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void check(MockHttpServletRequestBuilder request, String name) throws Exception {
        var response = mvc.perform(request.header("X-Internal-Key", "contract-key")
                .header("X-Caller-Service", "dispatch-service").contentType("application/json"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse();
        var actual = mapper.readTree(response.getContentAsByteArray());
        var expected = mapper.readTree(sample(name, ".response"));
        assertThat(actual).isEqualTo(expected);
    }
}