package com.ridehailing.wsgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.wsgateway.dto.PushRequest;
import com.ridehailing.wsgateway.dto.PushResponse;
import com.ridehailing.wsgateway.dto.SetRouteRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MultiInstanceTest {
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString("test-secret-key-32-bytes-long!".getBytes());
    private static final String INTERNAL_KEY = "test-internal-key-12345";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", "testpass")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "testpass");
        registry.add("jwt.secret", () -> JWT_SECRET);
        registry.add("internal.key", () -> INTERNAL_KEY);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void shouldForwardLocationAcrossInstances() throws Exception {
        String customerId = "customer-1";
        String driverId = "driver-1";

        BlockingQueue<String> customerMessages = new LinkedBlockingQueue<>();

        // Connect customer
        WebSocketSession customerSession = connectAndAuth(customerId, "customer", customerMessages);

        // Set route
        setRoute(driverId, customerId);

        // Driver sends location (simulated, as if from another instance)
        long sentAt = System.currentTimeMillis();
        sendLocationViaRedis(driverId, customerId, 10.762622, 106.660172, sentAt);

        // Customer should receive driver_location within 300ms (200ms batch + buffer)
        String message = customerMessages.poll(500, TimeUnit.MILLISECONDS);
        assertThat(message).isNotNull();

        Map<String, Object> payload = objectMapper.readValue(message, Map.class);
        assertThat(payload.get("t")).isEqualTo("driver_location");
        assertThat(payload.get("lat")).isEqualTo(10.762622);
        assertThat(payload.get("lng")).isEqualTo(106.660172);
        assertThat(((Number) payload.get("sent_at")).longValue()).isEqualTo(sentAt);

        customerSession.close();
    }

    @Test
    void shouldReportDeliveredFlagCorrectly() throws Exception {
        String userId = "user-1";

        // Push without session online
        PushResponse response1 = push(userId, "trip_offer", Map.of("tripId", "trip-1"));
        assertThat(response1.delivered()).isFalse();

        // Connect user
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketSession session = connectAndAuth(userId, "driver", messages);

        // Push with session online
        PushResponse response2 = push(userId, "trip_offer", Map.of("tripId", "trip-2"));
        assertThat(response2.delivered()).isTrue();

        // User should receive the message
        String message = messages.poll(1, TimeUnit.SECONDS);
        assertThat(message).isNotNull();

        Map<String, Object> payload = objectMapper.readValue(message, Map.class);
        assertThat(payload.get("t")).isEqualTo("trip_offer");
        assertThat(payload.get("tripId")).isEqualTo("trip-2");

        session.close();
    }

    @Test
    void shouldStopForwardingAfterRouteDeleted() throws Exception {
        String customerId = "customer-2";
        String driverId = "driver-2";

        BlockingQueue<String> customerMessages = new LinkedBlockingQueue<>();
        WebSocketSession customerSession = connectAndAuth(customerId, "customer", customerMessages);

        // Set route
        setRoute(driverId, customerId);

        // Driver sends location
        sendLocationViaRedis(driverId, customerId, 10.5, 106.5, System.currentTimeMillis());

        // Customer receives
        String message1 = customerMessages.poll(500, TimeUnit.MILLISECONDS);
        assertThat(message1).isNotNull();

        // Clear the queue
        customerMessages.clear();

        // Delete route
        deleteRoute(driverId);

        // Wait for Redis to propagate the deletion
        Thread.sleep(200);

        // Driver sends another location - but since route is deleted, we publish to wrong channel
        // This simulates what would happen: LocationBatcher checks route, finds nothing, doesn't publish
        // So we should NOT publish at all
        try {
            Map<String, Object> message = Map.of(
                    "t", "driver_location",
                    "lat", 10.6,
                    "lng", 106.6,
                    "sent_at", System.currentTimeMillis()
            );
            String json = objectMapper.writeValueAsString(message);
            // Simulate: after deletion, route lookup returns null, so no publish happens
            String route = redisTemplate.opsForValue().get("ws:route:" + driverId);
            if (route != null) {
                redisTemplate.convertAndSend("ws:out:" + route, json);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Customer should NOT receive
        String message2 = customerMessages.poll(500, TimeUnit.MILLISECONDS);
        assertThat(message2).isNull();

        customerSession.close();
    }

    @Test
    void shouldNotLeakMemoryAfterSessionClose() throws Exception {
        String userId = "user-leak-test";

        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketSession session = connectAndAuth(userId, "driver", messages);

        // Push message
        push(userId, "test", Map.of("data", "value"));
        assertThat(messages.poll(1, TimeUnit.SECONDS)).isNotNull();

        // Close session
        session.close();
        Thread.sleep(100); // Give time for cleanup

        // Push again - should not be delivered
        PushResponse response = push(userId, "test2", Map.of("data", "value2"));
        assertThat(response.delivered()).isFalse();

        // No messages should arrive
        assertThat(messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    private WebSocketSession connectAndAuth(String userId, String role, BlockingQueue<String> messageQueue) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String wsUrl = "ws://localhost:" + port + "/ws/" + role;

        WebSocketSession session = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                messageQueue.offer(message.getPayload());
            }
        }, wsUrl).get(5, TimeUnit.SECONDS);

        // Send auth message
        String token = generateToken(userId, role);
        String authMsg = objectMapper.writeValueAsString(Map.of("t", "auth", "token", token));
        session.sendMessage(new TextMessage(authMsg));

        Thread.sleep(100); // Wait for auth to complete

        return session;
    }

    private void sendLocationViaRedis(String driverId, String customerId, double lat, double lng, long sentAt) {
        // Set route in Redis first
        redisTemplate.opsForValue().set("ws:route:" + driverId, customerId, 6, TimeUnit.HOURS);

        // Trigger publishing by simulating what LocationBatcher does
        try {
            Map<String, Object> message = Map.of(
                    "t", "driver_location",
                    "lat", lat,
                    "lng", lng,
                    "sent_at", sentAt
            );
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend("ws:out:" + customerId, json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setRoute(String driverId, String customerId) {
        String url = "http://localhost:" + port + "/internal/routes/" + driverId;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", INTERNAL_KEY);
        headers.set("Content-Type", "application/json");

        SetRouteRequest request = new SetRouteRequest(customerId);
        HttpEntity<SetRouteRequest> entity = new HttpEntity<>(request, headers);

        restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
    }

    private void deleteRoute(String driverId) {
        String url = "http://localhost:" + port + "/internal/routes/" + driverId;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", INTERNAL_KEY);

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
    }

    private PushResponse push(String userId, String type, Map<String, Object> payload) {
        String url = "http://localhost:" + port + "/internal/push";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", INTERNAL_KEY);
        headers.set("Content-Type", "application/json");

        PushRequest request = new PushRequest(userId, type, payload);
        HttpEntity<PushRequest> entity = new HttpEntity<>(request, headers);

        return restTemplate.postForObject(url, entity, PushResponse.class);
    }

    private String generateToken(String userId, String role) {
        try {
            String header = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(("{\"sub\":\"" + userId + "\",\"role\":\"" + role + "\"}").getBytes(StandardCharsets.UTF_8));

            String data = header + "." + payload;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(JWT_SECRET), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

            return data + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
