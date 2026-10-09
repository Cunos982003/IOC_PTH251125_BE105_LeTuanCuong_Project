package com.ridehailing.userservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitListenerStartup implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(RabbitListenerStartup.class);

    private final RabbitListenerEndpointRegistry registry;
    private boolean started = false;

    public RabbitListenerStartup(RabbitListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (started) {
            return;
        }
        String[] listenerIds = {
            "tripEventListener"
        };
        for (String id : listenerIds) {
            try {
                registry.getListenerContainer(id).start();
                log.info("Started RabbitListener: {}", id);
            } catch (Exception e) {
                log.error("Failed to start RabbitListener {}: {}", id, e.getMessage());
            }
        }
        started = true;
    }
}