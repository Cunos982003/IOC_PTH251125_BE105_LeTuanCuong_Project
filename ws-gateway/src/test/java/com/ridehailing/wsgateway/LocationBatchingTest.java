package com.ridehailing.wsgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.profiles.active=test")
@Testcontainers
class LocationBatchingTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", "testpass")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "testpass");
        registry.add("jwt.secret", () -> Base64.getEncoder().encodeToString("test-secret-key-32-bytes-long!".getBytes()));
        registry.add("internal.key", () -> "test-internal-key");
        registry.add("services.location", () -> "http://localhost:9999");
        registry.add("websocket.location-batch-interval-ms", () -> "200");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final String jwtSecret = Base64.getEncoder().encodeToString("test-secret-key-32-bytes-long!".getBytes());
    private WireMockServer wireMock;

    @BeforeEach
    void setup() {
        wireMock = new WireMockServer(9999);
        wireMock.start();
        WireMock.configureFor("localhost", 9999);
    }

    @AfterEach
    void teardown() {
        wireMock.stop();
    }

    @Test
    void shouldBatchLocationUpdatesFrom50Drivers() throws Exception {
        stubFor(post(urlEqualTo("/internal/locations"))
                .willReturn(aResponse().withStatus(200)));

        List<TestWebSocketClient> clients = new ArrayList<>();
        CountDownLatch openLatch = new CountDownLatch(50);

        for (int i = 0; i < 50; i++) {
            String driverId = Integer.toString(i + 1);
            String token = createToken(driverId, "DRIVER");

            TestWebSocketClient client = new TestWebSocketClient(
                    new URI("ws://localhost:" + port + "/ws/driver"),
                    new CountDownLatch(1), new ArrayList<>());
            client.setOpenLatch(openLatch);

            client.connectBlocking(2, TimeUnit.SECONDS);
            client.send("{\"t\":\"auth\",\"token\":\"" + token + "\"}");
            clients.add(client);
        }

        openLatch.await(5, TimeUnit.SECONDS);
        Thread.sleep(500);

        for (int i = 0; i < 50; i++) {
            TestWebSocketClient client = clients.get(i);
            long now = System.currentTimeMillis();
            client.send("{\"t\":\"location\",\"lat\":" + (10.0 + i) + ",\"lng\":" + (20.0 + i) + ",\"sent_at\":" + now + "}");
        }

        Thread.sleep(500);

        List<com.github.tomakehurst.wiremock.verification.LoggedRequest> requests =
                findAll(postRequestedFor(urlEqualTo("/internal/locations"))
                        .withHeader("X-Internal-Key", equalTo("test-internal-key")));

        assertThat(requests.size()).isLessThanOrEqualTo(5);
        assertThat(requests.size()).isGreaterThan(0);

        for (TestWebSocketClient client : clients) {
            client.closeBlocking();
        }
    }

    @Test
    void shouldIgnoreFakeDriverIdInLocationMessage() throws Exception {
        List<Map<String, Object>> capturedBodies = new ArrayList<>();

        stubFor(post(urlEqualTo("/internal/locations"))
                .willReturn(aResponse().withStatus(200)));

        String token = createToken("123", "DRIVER");

        TestWebSocketClient client = new TestWebSocketClient(
                new URI("ws://localhost:" + port + "/ws/driver"),
                new CountDownLatch(1), new ArrayList<>());
        CountDownLatch openLatch = new CountDownLatch(1);
        client.setOpenLatch(openLatch);

        client.connectBlocking(2, TimeUnit.SECONDS);
        openLatch.await(1, TimeUnit.SECONDS);

        client.send("{\"t\":\"auth\",\"token\":\"" + token + "\"}");
        Thread.sleep(200);

        long now = System.currentTimeMillis();
        client.send("{\"t\":\"location\",\"driverId\":\"fakeDriver999\",\"lat\":10.0,\"lng\":20.0,\"sent_at\":" + now + "}");

        Thread.sleep(500);

        List<com.github.tomakehurst.wiremock.verification.LoggedRequest> requests =
                findAll(postRequestedFor(urlEqualTo("/internal/locations")));

        assertThat(requests.size()).isGreaterThan(0);

        for (var req : requests) {
            var locations = objectMapper.readTree(req.getBodyAsString());
            assertThat(locations.isArray()).isTrue();
            assertThat(locations.size()).isGreaterThan(0);

            for (var loc : locations) {
                assertThat(loc.path("driverId").asLong()).isEqualTo(123L);
                assertThat(loc.path("driverId").isIntegralNumber()).isTrue();
            }
        }

        client.closeBlocking();
    }

    private String createToken(String userId, String role) {
        try {
            String header = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(("{\"sub\":\"" + userId + "\",\"role\":\"" + role + "\"}").getBytes(StandardCharsets.UTF_8));

            String data = header + "." + payload;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(jwtSecret), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

            return data + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class TestWebSocketClient extends WebSocketClient {
        private final CountDownLatch closeLatch;
        private final List<String> closeReasons;
        private CountDownLatch openLatch;

        public TestWebSocketClient(URI serverUri, CountDownLatch closeLatch, List<String> closeReasons) {
            super(serverUri);
            this.closeLatch = closeLatch;
            this.closeReasons = closeReasons;
        }

        public void setOpenLatch(CountDownLatch latch) {
            this.openLatch = latch;
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            if (openLatch != null) {
                openLatch.countDown();
            }
        }

        @Override
        public void onMessage(String message) {
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closeReasons.add(reason);
            closeLatch.countDown();
        }

        @Override
        public void onError(Exception ex) {
        }
    }
}
