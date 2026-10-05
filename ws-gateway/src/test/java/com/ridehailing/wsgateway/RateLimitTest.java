package com.ridehailing.wsgateway;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.profiles.active=test")
@Testcontainers
class RateLimitTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", "testpass")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "testpass");
        registry.add("jwt.secret", () -> Base64.getEncoder().encodeToString("test-secret-key-32-bytes-long!".getBytes()));
        registry.add("internal.key", () -> "test-internal-key");
        registry.add("websocket.rate-limit-per-second", () -> "5");
    }

    @LocalServerPort
    private int port;

    private final String jwtSecret = Base64.getEncoder().encodeToString("test-secret-key-32-bytes-long!".getBytes());

    @Test
    void shouldAllowMessagesWithinRateLimit() throws Exception {
        String token = createToken("driver123", "driver");

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        List<String> messages = new ArrayList<>();

        TestWebSocketClient client = new TestWebSocketClient(
                new URI("ws://localhost:" + port + "/ws/driver"),
                closeLatch, new ArrayList<>(), messages);
        client.setOpenLatch(openLatch);

        client.connectBlocking(2, TimeUnit.SECONDS);
        openLatch.await(1, TimeUnit.SECONDS);

        client.send("{\"t\":\"auth\",\"token\":\"" + token + "\"}");
        Thread.sleep(200);

        for (int i = 0; i < 5; i++) {
            client.send("{\"t\":\"location\",\"lat\":10.0,\"lng\":20.0,\"sent_at\":" + System.currentTimeMillis() + "}");
        }

        Thread.sleep(200);

        long errorCount = messages.stream()
                .filter(m -> m.contains("RATE_LIMIT"))
                .count();

        assertThat(errorCount).isZero();
        assertThat(client.isOpen()).isTrue();

        client.closeBlocking();
    }

    @Test
    void shouldBlockMessagesExceedingRateLimit() throws Exception {
        String token = createToken("driver123", "driver");

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        List<String> messages = new ArrayList<>();

        TestWebSocketClient client = new TestWebSocketClient(
                new URI("ws://localhost:" + port + "/ws/driver"),
                closeLatch, new ArrayList<>(), messages);
        client.setOpenLatch(openLatch);

        client.connectBlocking(2, TimeUnit.SECONDS);
        openLatch.await(1, TimeUnit.SECONDS);

        client.send("{\"t\":\"auth\",\"token\":\"" + token + "\"}");
        Thread.sleep(200);

        for (int i = 0; i < 10; i++) {
            client.send("{\"t\":\"location\",\"lat\":10.0,\"lng\":20.0,\"sent_at\":" + System.currentTimeMillis() + "}");
        }

        Thread.sleep(300);

        long errorCount = messages.stream()
                .filter(m -> m.contains("RATE_LIMIT"))
                .count();

        assertThat(errorCount).isGreaterThan(0);

        client.closeBlocking();
    }

    @Test
    void shouldResetRateLimitAfterOneSecond() throws Exception {
        String token = createToken("driver123", "driver");

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        List<String> messages = new ArrayList<>();

        TestWebSocketClient client = new TestWebSocketClient(
                new URI("ws://localhost:" + port + "/ws/driver"),
                closeLatch, new ArrayList<>(), messages);
        client.setOpenLatch(openLatch);

        client.connectBlocking(2, TimeUnit.SECONDS);
        openLatch.await(1, TimeUnit.SECONDS);

        client.send("{\"t\":\"auth\",\"token\":\"" + token + "\"}");
        Thread.sleep(200);

        for (int i = 0; i < 5; i++) {
            client.send("{\"t\":\"location\",\"lat\":10.0,\"lng\":20.0,\"sent_at\":" + System.currentTimeMillis() + "}");
        }

        Thread.sleep(1100);
        messages.clear();

        for (int i = 0; i < 5; i++) {
            client.send("{\"t\":\"location\",\"lat\":10.0,\"lng\":20.0,\"sent_at\":" + System.currentTimeMillis() + "}");
        }

        Thread.sleep(200);

        long errorCount = messages.stream()
                .filter(m -> m.contains("RATE_LIMIT"))
                .count();

        assertThat(errorCount).isZero();

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
        private final List<String> messages;
        private CountDownLatch openLatch;

        public TestWebSocketClient(URI serverUri, CountDownLatch closeLatch, List<String> closeReasons, List<String> messages) {
            super(serverUri);
            this.closeLatch = closeLatch;
            this.closeReasons = closeReasons;
            this.messages = messages;
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
            messages.add(message);
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
