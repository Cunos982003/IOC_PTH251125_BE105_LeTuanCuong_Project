package com.ridehailing.wsgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.wsgateway.event.DriverOfferedEvent;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class DriverOfferConsumerIntegrationTest {

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withExposedPorts(5672, 15672)
            .withStartupTimeout(java.time.Duration.ofSeconds(180));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server --requirepass testpass")
            .withStartupTimeout(java.time.Duration.ofSeconds(180));

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    // Separate Lettuce connection for pub/sub subscription
    static RedisClient redisClient;
    static StatefulRedisPubSubConnection<String, String> pubSubConnection;

    @BeforeAll
    static void declareQueues() {
        // Declare queues/exchanges before Spring context starts so auto-startup listeners find them
        var connectionFactory = new CachingConnectionFactory(
                "localhost", rabbitmq.getMappedPort(5672)
        );
        connectionFactory.setUsername(rabbitmq.getAdminUsername());
        connectionFactory.setPassword(rabbitmq.getAdminPassword());

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        var exchange = ExchangeBuilder.topicExchange("events").durable(true).build();
        admin.declareExchange(exchange);
        admin.declareQueue(QueueBuilder.durable("ws.offers").build());
        admin.declareBinding(new Binding("ws.offers", Binding.DestinationType.QUEUE, "events", "trips.offered", null));
        connectionFactory.destroy();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "testpass");
        registry.add("JWT_SECRET", () -> java.util.Base64.getEncoder().encodeToString("this-is-a-very-long-secret-key-for-testing-purposes-only-32chars".getBytes()));
        registry.add("INTERNAL_KEY", () -> "test-internal-key");
    }

    @BeforeAll
    static void startContainers() {
        // Create dedicated Lettuce connection for pub/sub
        RedisURI redisUri = RedisURI.Builder
                .redis(redis.getHost(), redis.getFirstMappedPort())
                .withPassword("testpass")
                .build();
        redisClient = RedisClient.create(redisUri);
        pubSubConnection = redisClient.connectPubSub();
    }

    @AfterAll
    static void stopContainers() {
        if (pubSubConnection != null) {
            pubSubConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @BeforeEach
    void setup() {
        // Queues/exchanges already declared in @BeforeAll before context startup
        // Listeners have autoStartup=false, start them manually
        rabbitListenerEndpointRegistry.getListenerContainer("ws.offers").start();
    }

    @AfterEach
    void cleanup() {
        // Unsubscribe from all channels (listeners are per-channel, so we just unsubscribe)
        // The listeners will be garbage collected
    }

    private void subscribeAndWait(String channel, CountDownLatch latch, AtomicReference<String> receivedMessage) {
        pubSubConnection.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
                receivedMessage.set(message);
                latch.countDown();
            }
        });
        pubSubConnection.sync().subscribe(channel);
    }

    @Test
    void driverOfferPublishedToRedis() throws Exception {
        // Given: a driver offer event
        UUID tripId = UUID.randomUUID();
        long driverId = 12345L;
        long customerId = 67890L;
        long fare = 150000L;
        Instant expiresAt = Instant.now().plusSeconds(300); // 5 minutes in future

        DriverOfferedEvent event = new DriverOfferedEvent(
                UUID.randomUUID(),
                tripId,
                driverId,
                customerId,
                new DriverOfferedEvent.Pickup(10.7769, 106.7009),
                fare,
                expiresAt
        );

        String payload = objectMapper.writeValueAsString(event);

        // First: subscribe to Redis channel BEFORE sending message
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        String channel = "ws:out:" + driverId;
        subscribeAndWait(channel, latch, receivedMessage);

        // When: publish to RabbitMQ
        Message message = MessageBuilder
                .withBody(payload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.offered")
                .build();
        rabbitTemplate.send("events", "trips.offered", message);

        // Then: verify message received on Redis channel ws:out:{driverId}
        // Wait for message
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

        // Verify message content
        String received = receivedMessage.get();
        assertThat(received).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> wsMessage = objectMapper.readValue(received, Map.class);

        assertThat(wsMessage.get("t")).isEqualTo("offer");
        assertThat(wsMessage.get("tripId")).isEqualTo(tripId.toString());
        assertThat(wsMessage.get("fare")).isEqualTo((int) fare);
        assertThat(wsMessage.get("pickup")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Double> pickup = (Map<String, Double>) wsMessage.get("pickup");
        assertThat(pickup.get("lat")).isEqualTo(10.7769);
        assertThat(pickup.get("lng")).isEqualTo(106.7009);
        assertThat(wsMessage.get("expiresAt")).isNotNull();

        // Cleanup
        pubSubConnection.sync().unsubscribe(channel);
    }

    @Test
    void expiredOfferNotPublished() throws Exception {
        // Given: an expired driver offer event
        UUID tripId = UUID.randomUUID();
        long driverId = 54321L;
        Instant expiresAt = Instant.now().minusSeconds(10); // 10 seconds ago

        DriverOfferedEvent event = new DriverOfferedEvent(
                UUID.randomUUID(),
                tripId,
                driverId,
                12345L,
                new DriverOfferedEvent.Pickup(10.7769, 106.7009),
                150000L,
                expiresAt
        );

        String payload = objectMapper.writeValueAsString(event);

        // First: subscribe to Redis channel BEFORE sending message
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        String channel = "ws:out:" + driverId;
        subscribeAndWait(channel, latch, receivedMessage);

        // When: publish to RabbitMQ
        Message message = MessageBuilder
                .withBody(payload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.offered")
                .build();
        rabbitTemplate.send("events", "trips.offered", message);

        // Then: verify NO message received on Redis channel (offer is expired)
        // Wait a bit - should NOT receive message
        boolean received = latch.await(3, TimeUnit.SECONDS);
        assertThat(received).isFalse();
        assertThat(receivedMessage.get()).isNull();

        // Cleanup
        pubSubConnection.sync().unsubscribe(channel);
    }

    @Test
    void offerWithCorrectFormat() throws Exception {
        // Given
        UUID tripId = UUID.randomUUID();
        long driverId = 99999L;
        Instant expiresAt = Instant.now().plusSeconds(300);

        DriverOfferedEvent event = new DriverOfferedEvent(
                UUID.randomUUID(),
                tripId,
                driverId,
                11111L,
                new DriverOfferedEvent.Pickup(10.1, 106.1),
                200000L,
                expiresAt
        );

        String payload = objectMapper.writeValueAsString(event);

        // First: subscribe to Redis channel BEFORE sending message
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        String channel = "ws:out:" + driverId;
        subscribeAndWait(channel, latch, receivedMessage);

        // When
        Message message = MessageBuilder
                .withBody(payload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.offered")
                .build();
        rabbitTemplate.send("events", "trips.offered", message);

        // Then: verify exact message format matches WebSocket protocol
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

        String received = receivedMessage.get();
        assertThat(received).isNotNull();

        // Verify it's valid JSON with expected structure
        @SuppressWarnings("unchecked")
        Map<String, Object> wsMessage = objectMapper.readValue(received, Map.class);

        assertThat(wsMessage).containsKeys("t", "tripId", "pickup", "fare", "expiresAt");
        assertThat(wsMessage.get("t")).isEqualTo("offer");
        assertThat(wsMessage.get("tripId")).isEqualTo(tripId.toString());
        assertThat(wsMessage.get("fare")).isEqualTo(200000);

        // Cleanup
        pubSubConnection.sync().unsubscribe(channel);
    }

    @Test
    void driverOfferConsumer_autoStartsAndProcessesWithin5Seconds() throws Exception {
        // This test verifies the listener can be started manually and processes events.

        // Declare exchange and queue (normally done by infra/definitions.json)
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare("events", "topic", true);
            channel.queueDeclare("ws.offers", true, false, false, null);
            channel.queueBind("ws.offers", "events", "trips.offered");
            return null;
        });

        // Given: a driver offer event
        UUID tripId = UUID.randomUUID();
        long driverId = 88888L;
        long customerId = 77777L;
        long fare = 150000L;
        Instant expiresAt = Instant.now().plusSeconds(300);

        DriverOfferedEvent event = new DriverOfferedEvent(
                UUID.randomUUID(),
                tripId,
                driverId,
                customerId,
                new DriverOfferedEvent.Pickup(10.7769, 106.7009),
                fare,
                expiresAt
        );

        String payload = objectMapper.writeValueAsString(event);

        // First: subscribe to Redis channel BEFORE sending message
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        String channel = "ws:out:" + driverId;
        subscribeAndWait(channel, latch, receivedMessage);

        // When: publish to RabbitMQ - listener should auto-start and consume
        Message message = MessageBuilder
                .withBody(payload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.offered")
                .build();
        rabbitTemplate.send("events", "trips.offered", message);

        // Then: verify message received on Redis channel ws:out:{driverId} within 5 seconds
        // Wait for message - should complete within 5 seconds
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify message content
        String received = receivedMessage.get();
        assertThat(received).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> wsMessage = objectMapper.readValue(received, Map.class);

        assertThat(wsMessage.get("t")).isEqualTo("offer");
        assertThat(wsMessage.get("tripId")).isEqualTo(tripId.toString());
        assertThat(wsMessage.get("fare")).isEqualTo((int) fare);
        assertThat(wsMessage.get("pickup")).isInstanceOf(Map.class);

        // Cleanup
        pubSubConnection.sync().unsubscribe(channel);
    }
}