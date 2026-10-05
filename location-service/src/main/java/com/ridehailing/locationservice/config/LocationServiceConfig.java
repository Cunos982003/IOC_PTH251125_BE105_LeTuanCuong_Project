package com.ridehailing.locationservice.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import com.ridehailing.locationservice.internal.InternalKeyFilter;

@Configuration
public class LocationServiceConfig {

    @Bean
    @Primary
    @Profile("!test")
    public ClockProvider clockProvider() {
        return ClockProvider.system();
    }

    @Bean
    @Profile("test")
    public ClockProvider testClockProvider() {
        return new ClockProvider.FixedClockProvider(java.time.Instant.EPOCH);
    }

    @Bean
    public FilterRegistrationBean<InternalKeyFilter> internalKeyFilterRegistration(InternalKeyFilter filter) {
        FilterRegistrationBean<InternalKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(1);
        return registration;
    }
}