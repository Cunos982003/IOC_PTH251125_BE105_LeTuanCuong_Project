package com.ridehailing.userservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TripCompletedEventContractTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void consumerParsesContractEvent() throws Exception {
        String fixture = fixture("TripCompleted");
        JsonNode node = objectMapper.readTree(fixture);

        // Parse with exact fields from contract
        assertThat(node.has("eventType")).isTrue();
        assertThat(node.get("eventType").asText()).isEqualTo("TripCompleted");

        // Parse with extra unknown field (tolerance test)
        String withExtra = fixture.replace("}", ", \"unknownField\": \"should-be-ignored\"}");
        JsonNode extraNode = objectMapper.readTree(withExtra);
        assertThat(extraNode.get("eventType").asText()).isEqualTo("TripCompleted");

        // Verify Redis stream write/read works
        try (Jedis jedis = new Jedis(redis.getHost(), redis.getFirstMappedPort())) {
            jedis.xgroupCreate("events.trips", "user-grp", StreamEntryID.LAST_ENTRY, true);

            Map<String, String> fields = Map.of("data", fixture);
            StreamEntryID id = jedis.xadd("events.trips", StreamEntryID.NEW_ENTRY, fields);
            assertThat(id).isNotNull();

            var entries = jedis.xreadGroup("user-grp", "user-1", 1, 1000, false,
                Map.entry("events.trips", StreamEntryID.UNRECEIVED_ENTRY));
            assertThat(entries).isNotEmpty();
            assertThat(entries.get(0).getValue()).hasSize(1);
            assertThat(entries.get(0).getValue().get(0).getFields().get("data")).isEqualTo(fixture);
        }
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/events/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}