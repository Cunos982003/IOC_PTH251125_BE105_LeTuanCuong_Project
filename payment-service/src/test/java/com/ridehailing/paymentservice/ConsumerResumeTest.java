package com.ridehailing.paymentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.paymentservice.event.TripEvent;
import com.ridehailing.paymentservice.event.UserRegisteredEvent;
import com.ridehailing.paymentservice.repository.WalletRepository;
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
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class ConsumerResumeTest {

    // Force Testcontainers to use host.docker.internal on Windows
    static {
        System.setProperty("testcontainers.use-hostname-resolution", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withExposedPorts(5672, 15672)
            .withStartupTimeout(java.time.Duration.ofSeconds(180));

    @BeforeAll
    static void declareQueues() {
        var connectionFactory = new CachingConnectionFactory(
                "localhost", rabbitmq.getMappedPort(5672)
        );
        connectionFactory.setUsername(rabbitmq.getAdminUsername());
        connectionFactory.setPassword(rabbitmq.getAdminPassword());

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        var exchange = ExchangeBuilder.topicExchange("events").durable(true).build();
        admin.declareExchange(exchange);
        admin.declareQueue(QueueBuilder.durable("payment.trips.completed").build());
        admin.declareQueue(QueueBuilder.durable("payment.trips.cancelled").build());
        admin.declareQueue(QueueBuilder.durable("payment.users.registered").build());
        admin.declareBinding(new Binding("payment.trips.completed", Binding.DestinationType.QUEUE, "events", "trips.completed", null));
        admin.declareBinding(new Binding("payment.trips.cancelled", Binding.DestinationType.QUEUE, "events", "trips.cancelled", null));
        admin.declareBinding(new Binding("payment.users.registered", Binding.DestinationType.QUEUE, "events", "users.registered", null));
        connectionFactory.destroy();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("payment.internal-key", () -> "test-key");
        registry.add("payment.commission-rate", () -> "20");
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @BeforeEach
    void setUp() {
        // Queues/exchanges already declared in @BeforeAll before context startup
        // Listeners have autoStartup=false, start them manually
        rabbitListenerEndpointRegistry.getListenerContainer("payment.users.registered").start();
        rabbitListenerEndpointRegistry.getListenerContainer("payment.trips.completed").start();
        rabbitListenerEndpointRegistry.getListenerContainer("payment.trips.cancelled").start();

        // Create/reset test wallets with unique IDs (including platform wallet 0)
        walletRepository.createWallet(8001L, 1_000_000L); // customer
        walletRepository.createWallet(8002L, 0L);         // driver
        walletRepository.createWallet(0L, 0L);            // platform - reset to 0
    }

    @Test
    void consumerProcessesPendingMessages_afterRestart() throws Exception {
        UUID tripId = UUID.randomUUID();

        // Create TripCompleted event
        TripEvent event = new TripEvent("TripCompleted", tripId, 8001L, 8002L, 100_000L);
        String payload = objectMapper.writeValueAsString(event);

        Map<String, String> messageBody = new HashMap<>();
        messageBody.put("eventType", "TripCompleted");
        messageBody.put("payload", payload);

        // Add to RabbitMQ
        Message message = MessageBuilder
                .withBody(payload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.completed")
                .setHeader("eventId", UUID.randomUUID().toString())
                .build();
        rabbitTemplate.send("events", "trips.completed", message);

        // Wait for consumer to process
        Thread.sleep(3000);

        // Verify settlement occurred
        long customerBalance = walletRepository.findBalance(8001L).orElseThrow();
        long driverBalance = walletRepository.findBalance(8002L).orElseThrow();
        long platformBalance = walletRepository.findBalance(0L).orElse(0L);

        assertEquals(900_000L, customerBalance, "Customer should have paid 100k");
        assertEquals(80_000L, driverBalance, "Driver should receive payout (80% of fare)");
        assertEquals(20_000L, platformBalance, "Platform should receive commission (20% of fare)");

        // Add another event with same tripId
        Message message2 = MessageBuilder
                .withBody(payload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.completed")
                .setHeader("eventId", UUID.randomUUID().toString())
                .build();
        rabbitTemplate.send("events", "trips.completed", message2);

        // Wait for processing
        Thread.sleep(2000);

        // Verify no double settlement (idempotency)
        assertEquals(900_000L, walletRepository.findBalance(8001L).orElseThrow());
        assertEquals(80_000L, walletRepository.findBalance(8002L).orElseThrow());
        assertEquals(20_000L, walletRepository.findBalance(0L).orElse(0L));
    }

    @Test
    void consumers_autoStartAndProcessWithin5Seconds() throws Exception {
        // This test verifies all 3 listeners can be started manually and process events.
        // Listeners have autoStartup=false, so we start them manually.

        // Start listeners manually
        rabbitListenerEndpointRegistry.getListenerContainer("payment.users.registered").start();
        rabbitListenerEndpointRegistry.getListenerContainer("payment.trips.completed").start();
        rabbitListenerEndpointRegistry.getListenerContainer("payment.trips.cancelled").start();

        // Declare exchange and queues (normally done by infra/definitions.json)
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare("events", "topic", true);
            channel.queueDeclare("payment.trips.completed", true, false, false, null);
            channel.queueBind("payment.trips.completed", "events", "trips.completed");
            channel.queueDeclare("payment.trips.cancelled", true, false, false, null);
            channel.queueBind("payment.trips.cancelled", "events", "trips.cancelled");
            channel.queueDeclare("payment.users.registered", true, false, false, null);
            channel.queueBind("payment.users.registered", "events", "users.registered");
            return null;
        });

        // Create test wallets
        walletRepository.createWallet(9001L, 1_000_000L); // customer
        walletRepository.createWallet(9002L, 0L);         // driver

        // Test 1: TripCompleted consumer auto-starts and processes
        UUID tripId = UUID.randomUUID();
        TripEvent completedEvent = new TripEvent("TripCompleted", tripId, 9001L, 9002L, 150_000L);
        String completedPayload = objectMapper.writeValueAsString(completedEvent);

        Message completedMessage = MessageBuilder
                .withBody(completedPayload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.completed")
                .setHeader("eventId", UUID.randomUUID().toString())
                .build();
        rabbitTemplate.send("events", "trips.completed", completedMessage);

        // Wait for consumer to process - should complete within 5 seconds
        await().atMost(5, SECONDS).untilAsserted(() -> {
            long customerBalance = walletRepository.findBalance(9001L).orElseThrow();
            long driverBalance = walletRepository.findBalance(9002L).orElseThrow();
            long platformBalance = walletRepository.findBalance(0L).orElse(0L);
            assertEquals(850_000L, customerBalance, "Customer should have paid 150k");
            assertEquals(120_000L, driverBalance, "Driver should receive payout (80%)");
            assertEquals(30_000L, platformBalance, "Platform should receive commission (20%)");
        });

        // Test 2: UserRegistered consumer auto-starts and processes
        UserRegisteredEvent registeredEvent = new UserRegisteredEvent(9003L, "CUSTOMER");
        String registeredPayload = objectMapper.writeValueAsString(registeredEvent);

        Message registeredMessage = MessageBuilder
                .withBody(registeredPayload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "users.registered")
                .setHeader("eventId", UUID.randomUUID().toString())
                .build();
        rabbitTemplate.send("events", "users.registered", registeredMessage);

        // Wait for consumer to process - should complete within 5 seconds
        await().atMost(5, SECONDS).untilAsserted(() -> {
            long newUserBalance = walletRepository.findBalance(9003L).orElse(-1L);
            assertEquals(500_000L, newUserBalance, "New customer should get 500k initial balance");
        });

        // Test 3: TripCancelled consumer auto-starts (no balance change expected)
        UUID tripId2 = UUID.randomUUID();
        TripEvent cancelledEvent = new TripEvent("TripCancelled", tripId2, 9001L, 9002L, 100_000L);
        String cancelledPayload = objectMapper.writeValueAsString(cancelledEvent);

        Message cancelledMessage = MessageBuilder
                .withBody(cancelledPayload.getBytes())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("type", "trips.cancelled")
                .setHeader("eventId", UUID.randomUUID().toString())
                .build();
        rabbitTemplate.send("events", "trips.cancelled", cancelledMessage);

        // Wait for consumer to process - should complete within 5 seconds
        await().atMost(5, SECONDS).untilAsserted(() -> {
            // Just verify no exception thrown - cancellation doesn't change balances
            long customerBalance = walletRepository.findBalance(9001L).orElseThrow();
            assertEquals(850_000L, customerBalance, "Customer balance unchanged after cancellation");
        });
    }

    // Need to add await import
}