package com.ridehailing.wsgateway;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableRabbit
public class WsGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(WsGatewayApplication.class, args);
    }
}
