package com.ridehailing.wsgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.profiles.active=test")
@Testcontainers
class WebSocketAuthTest {

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
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final String jwtSecret = Base64.getEncoder().encodeToString("test-secret-key-32-bytes-long!".getBytes());

    @Test
    void shouldCloseConnectionWhenNoAuthWithin5Seconds() throws Exception {
        CountDownLatch closeLatch = new CountDownLatch(1);
        List<String> closeReasons = new ArrayList<>();

        TestWebSocketClient client = new TestWebSocketClient(
                new URI("ws://localhost:" + port + "/ws/driver"),
                closeLatch, closeReasons);

        client.connectBlocking(2, TimeUnit.SECONDS);
        boolean closed = closeLatch.await(6, TimeUnit.SECONDS);

        assertThat(closed).isTrue();
        assertThat(closeReasons.get(0)).contains("Auth timeout");
    }

    @Test
    void shouldCloseConnectionOnInvalidToken() throws Exception {
        CountDownLatch closeLatch = new CountDownLatch(1);
        List<String> closeReasons = new ArrayList<>();

        TestWebSocketClient client = new TestWebSocketClient(
                new URI("ws://localhost:" + port + "/ws/driver"),
                closeLatch, closeReasons);

        client.connectBlocking(2, TimeUnit.SECONDS);
        client.send("{\"t\":\"auth\",\"token\":\"invalid.token.here\"}");

        boolean closed = closeLatch.await(2, TimeUnit.SECONDS);

        assertThat(closed).isTrue();
        assertThat(closeReasons.get(0)).contains("Invalid token");
    }

    @Test
    void shouldCloseConnectionOnRoleMismatch() throws Exception {
        String token = createToken("driver123", "driver");

        CountDownLatch closeLatch = new CountDownLatch(1);
        List<String> closeReasons = new ArrayList<>();

        TestWebSocketClient client = new TestWebSocketClient(
                new URI("ws://localhost:" + port + "/ws/customer"),
                closeLatch, closeReasons);

        client.connectBlocking(2, TimeUnit.SECONDS);
        client.send("{\"t\":\"auth\",\"token\":\"" + token + "\"}");

        boolean closed = closeLatch.await(2, TimeUnit.SECONDS);

        assertThat(closed).isTrue();
        assertThat(closeReasons.get(0)).contains("Role mismatch");
    }

    @Test
    void shouldAcceptValidAuth() throws Exception {
        String token = createToken("driver123", "driver");

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        List<String> closeReasons = new ArrayList<>();

        TestWebSocketClient client = new TestWebSocketClient(
                new URI("ws://localhost:" + port + "/ws/driver"),
                closeLatch, closeReasons);
        client.setOpenLatch(openLatch);

        client.connectBlocking(2, TimeUnit.SECONDS);
        openLatch.await(1, TimeUnit.SECONDS);

        client.send("{\"t\":\"auth\",\"token\":\"" + token + "\"}");

        Thread.sleep(1000);

        assertThat(client.isOpen()).isTrue();
        client.closeBlocking();
    }

    @Test
    void shouldRejectOversizedMessage() throws Exception {
        String token = createToken("driver123", "driver");

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        List<String> messages = new ArrayList<>();
        List<String> closeReasons = new ArrayList<>();

        TestWebSocketClient client = new TestWebSocketClient(
                new URI("ws://localhost:" + port + "/ws/driver"),
                closeLatch, closeReasons);
        client.setOpenLatch(openLatch);
        client.setMessageLatch(messageLatch, messages);

        client.connectBlocking(2, TimeUnit.SECONDS);
        openLatch.await(1, TimeUnit.SECONDS);

        client.send("{\"t\":\"auth\",\"token\":\"" + token + "\"}");
        Thread.sleep(200);

        String largeMessage = "{\"t\":\"location\",\"lat\":0,\"lng\":0,\"data\":\"" + "x".repeat(5000) + "\"}";
        client.send(largeMessage);

        boolean gotMessage = messageLatch.await(2, TimeUnit.SECONDS);
        assertThat(gotMessage).isTrue();

        Map<String, Object> error = objectMapper.readValue(messages.get(0), Map.class);
        assertThat(error.get("t")).isEqualTo("error");
        assertThat(error.get("code")).isEqualTo("MESSAGE_TOO_LARGE");

        client.closeBlocking();
    }

    @Test
    void shouldEnforceRateLimit() throws Exception {
        String token = createToken("driver123", "driver");

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        List<String> messages = new ArrayList<>();
        List<String> closeReasons = new ArrayList<>();

        TestWebSocketClient client = new TestWebSocketClient(
                new URI("ws://localhost:" + port + "/ws/driver"),
                closeLatch, closeReasons);
        client.setOpenLatch(openLatch);
        client.setMessageLatch(messageLatch, messages);

        client.connectBlocking(2, TimeUnit.SECONDS);
        openLatch.await(1, TimeUnit.SECONDS);

        client.send("{\"t\":\"auth\",\"token\":\"" + token + "\"}");
        Thread.sleep(200);

        for (int i = 0; i < 10; i++) {
            client.send("{\"t\":\"location\",\"lat\":10.0,\"lng\":20.0,\"sent_at\":" + System.currentTimeMillis() + "}");
        }

        boolean gotMessage = messageLatch.await(2, TimeUnit.SECONDS);
        assertThat(gotMessage).isTrue();

        Map<String, Object> error = objectMapper.readValue(messages.get(0), Map.class);
        assertThat(error.get("t")).isEqualTo("error");
        assertThat(error.get("code")).isEqualTo("RATE_LIMIT");

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
        private CountDownLatch messageLatch;
        private List<String> messages;

        public TestWebSocketClient(URI serverUri, CountDownLatch closeLatch, List<String> closeReasons) {
            super(serverUri);
            this.closeLatch = closeLatch;
            this.closeReasons = closeReasons;
        }

        public void setOpenLatch(CountDownLatch latch) {
            this.openLatch = latch;
        }

        public void setMessageLatch(CountDownLatch latch, List<String> messages) {
            this.messageLatch = latch;
            this.messages = messages;
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            if (openLatch != null) {
                openLatch.countDown();
            }
        }

        @Override
        public void onMessage(String message) {
            if (messages != null) {
                messages.add(message);
                if (messageLatch != null) {
                    messageLatch.countDown();
                }
            }
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
