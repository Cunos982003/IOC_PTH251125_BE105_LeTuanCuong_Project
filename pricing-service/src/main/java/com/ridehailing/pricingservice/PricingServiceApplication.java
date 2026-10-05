package com.ridehailing.pricingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PricingServiceApplication {
    public static void main(String[] args) {
        Thread.ofVirtual().factory();
        SpringApplication.run(PricingServiceApplication.class, args);
    }
}
