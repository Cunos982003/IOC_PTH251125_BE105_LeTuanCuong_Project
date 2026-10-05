package com.ridehailing.apigateway.config;

import com.ridehailing.apigateway.security.JwtVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityConfig {

    @Bean
    public JwtVerifier jwtVerifier(GatewayConfig gatewayConfig) {
        return new JwtVerifier(gatewayConfig.getJwtSecret());
    }
}
