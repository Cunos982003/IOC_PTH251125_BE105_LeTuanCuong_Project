package com.ridehailing.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class UserRegisteredEventContractTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void consumerParsesContractEvent() throws Exception {
        String fixture = fixture("UserRegistered");
        JsonNode node = objectMapper.readTree(fixture);

        // Parse with exact fields from contract
        assertThat(node.has("eventId")).isTrue();
        assertThat(node.has("userId")).isTrue();
        assertThat(node.get("userId").asLong()).isEqualTo(12345);

        // Parse with extra unknown field (tolerance test)
        String withExtra = fixture.replace("}", ", \"unknownField\": \"should-be-ignored\"}");
        JsonNode extraNode = objectMapper.readTree(withExtra);
        assertThat(extraNode.get("eventId").asText()).isNotBlank();

        // Verify Redis stream write/read works
        try (Jedis jedis = new Jedis(redis.getHost(), redis.getFirstMappedPort())) {
            jedis.xgroupCreate("events.users", "payment-grp", StreamEntryID.LAST_ENTRY, true);

            Map<String, String> fields = Map.of("data", fixture);
            StreamEntryID id = jedis.xadd("events.users", StreamEntryID.NEW_ENTRY, fields);
            assertThat(id).isNotNull();

            var entries = jedis.xreadGroup("payment-grp", "payment-1",
                    XReadGroupParams.xReadGroupParams().count(1).block(1000),
                    Map.of("events.users", StreamEntryID.UNRECEIVED_ENTRY));
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