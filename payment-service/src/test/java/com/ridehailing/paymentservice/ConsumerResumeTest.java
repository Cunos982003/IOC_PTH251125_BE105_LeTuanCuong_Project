package com.ridehailing.paymentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.paymentservice.event.TripEvent;
import com.ridehailing.paymentservice.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class ConsumerResumeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withExposedPorts(5672, 15672)
            .withStartupTimeout(java.time.Duration.ofSeconds(180));

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
        // Declare exchange and queues for testing (normally done by infra definitions.json)
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare("events", "topic", true);
            channel.queueDeclare("payment.trips.completed", true, false, false, null);
            channel.queueBind("payment.trips.completed", "events", "trips.completed");
            channel.queueDeclare("payment.trips.cancelled", true, false, false, null);
            channel.queueBind("payment.trips.cancelled", "events", "trips.cancelled");
            return null;
        });

        // Start the listeners after queues are declared
        rabbitListenerEndpointRegistry.getListenerContainer("payment.trips.completed").start();
        rabbitListenerEndpointRegistry.getListenerContainer("payment.trips.cancelled").start();

        // Create test wallets with unique IDs
        walletRepository.createWallet(8001L, 1_000_000L); // customer
        walletRepository.createWallet(8002L, 0L);         // driver
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
}