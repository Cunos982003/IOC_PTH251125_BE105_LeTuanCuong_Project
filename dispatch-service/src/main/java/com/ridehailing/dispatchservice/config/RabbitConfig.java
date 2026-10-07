package com.ridehailing.dispatchservice.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;

@Configuration
public class RabbitConfig {

    @Bean
    @Primary
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          RabbitCallbackRegistry callbackRegistry) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);

        // Set up shared confirm callback that delegates to registry
        template.setConfirmCallback((correlationData, ack, cause) -> {
            callbackRegistry.handleConfirm(correlationData, ack, cause);
        });

        // Set up shared return callback that delegates to registry
        template.setReturnsCallback(returned -> {
            callbackRegistry.handleReturn(returned);
        });

        return template;
    }
}