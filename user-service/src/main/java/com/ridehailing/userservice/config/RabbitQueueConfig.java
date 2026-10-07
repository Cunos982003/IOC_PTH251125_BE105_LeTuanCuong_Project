package com.ridehailing.userservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitQueueConfig {

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange("events", true, false);
    }

    @Bean
    public Queue userTripsQueue() {
        return new Queue("user.trips", true);
    }

    @Bean
    public Queue userRegisteredQueue() {
        return new Queue("user.registered", true);
    }

    @Bean
    public Binding userTripsCompletedBinding(TopicExchange eventsExchange, Queue userTripsQueue) {
        return BindingBuilder.bind(userTripsQueue).to(eventsExchange).with("trips.completed");
    }

    @Bean
    public Binding userTripsCancelledBinding(TopicExchange eventsExchange, Queue userTripsQueue) {
        return BindingBuilder.bind(userTripsQueue).to(eventsExchange).with("trips.cancelled");
    }

    @Bean
    public Binding userRegisteredBinding(TopicExchange eventsExchange, Queue userRegisteredQueue) {
        return BindingBuilder.bind(userRegisteredQueue).to(eventsExchange).with("users.registered");
    }

    // RabbitAdmin auto-declares all queues/exchanges/bindings on startup
    @Bean
    public RabbitAdmin rabbitAdmin(org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    // Configure listener container factory with proper settings for retry/DLQ
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(3);
        return factory;
    }
}