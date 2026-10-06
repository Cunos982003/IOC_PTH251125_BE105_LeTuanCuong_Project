package com.ridehailing.userservice;

import com.ridehailing.userservice.web.InternalController;
import com.ridehailing.userservice.security.InternalKeyFilter;
import com.ridehailing.userservice.repository.UserRepository;
import com.ridehailing.userservice.model.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = InternalHttpContractTest.Config.class, properties = {"INTERNAL_KEY=contract-key", "internal.key=contract-key", "payment.internal-key=contract-key"})
@AutoConfigureMockMvc
class InternalHttpContractTest {
    @org.springframework.context.annotation.Configuration
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration", "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"})
    @Import({InternalController.class, InternalKeyFilter.class})
    static class Config {
        @org.springframework.context.annotation.Bean
        org.springframework.security.web.SecurityFilterChain contractSecurity(org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean UserRepository users;

    @Test
    void responsesMatchContractSamples() throws Exception {
        when(users.findById(12345L)).thenReturn(java.util.Optional.of(new User(12345L, "x", "secret", "DRIVER", "Nguyen Van A", "x", java.time.Instant.EPOCH)));
        check(get("/internal/users/12345"), "user-get-user");
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