package com.ridehailing.locationservice;

import com.ridehailing.locationservice.telemetry.TelemetryController;
import com.ridehailing.locationservice.internal.InternalKeyFilter;
import com.ridehailing.locationservice.redis.LocationRedisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = InternalHttpContractTest.Config.class, properties = {"INTERNAL_KEY=contract-key", "internal.key=contract-key", "payment.internal-key=contract-key"})
@AutoConfigureMockMvc
class InternalHttpContractTest {
    @org.springframework.context.annotation.Configuration
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration", "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"})
    @Import({TelemetryController.class, InternalKeyFilter.class})
    static class Config {
        @Bean
        LocationRedisService locationRedisService() {
            return new LocationRedisService(null, null, null, null) {
                @Override
                public java.util.List<Long> updateLocations(java.util.List<LocationUpdate> updates) {
                    return java.util.List.of(67890L);
                }
                @Override
                public java.util.List<NearbyDriver> getNearbyDrivers(double lat, double lng, double radiusKm, int limit) {
                    return java.util.List.of(new NearbyDriver(67890L, 150L), new NearbyDriver(67891L, 320L));
                }
                @Override
                public long getDriverCount(double lat, double lng, double radiusKm) {
                    return 15L;
                }
                @Override
                public void markBusy(long driverId, String tripId) {
                }
                @Override
                public void markFree(long driverId) {
                }
            };
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void responsesMatchContractSamples() throws Exception {
        check(post("/internal/locations").content(sample("location-post-locations", "")), "location-post-locations");
        check(get("/internal/drivers/nearby?lat=10.7769&lng=106.7009&radiusM=2000&limit=10"), "location-get-nearby");
        check(get("/internal/drivers/count?lat=10.7769&lng=106.7009&radiusM=2000"), "location-get-count");
        check(post("/internal/drivers/67890/busy").content(sample("location-post-busy", "")), "location-post-busy");
        check(post("/internal/drivers/67890/free").content(sample("location-post-free", "")), "location-post-free");
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