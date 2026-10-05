package com.ridehailing.apigateway.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(GatewayConfig config) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        String origins = config.getCors().getAllowedOrigins();
        if (origins != null && !origins.isBlank()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
            cors.setAllowedMethods(Arrays.asList(config.getCors().getAllowedMethods().split(",")));
            cors.setAllowedHeaders(Arrays.asList(config.getCors().getAllowedHeaders().split(",")));
            cors.setExposedHeaders(java.util.List.of("X-Request-Id"));
            cors.setAllowCredentials(config.getCors().isAllowCredentials());
            source.registerCorsConfiguration("/api/v1/**", cors);
        }
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(0);
        return registration;
    }
}
