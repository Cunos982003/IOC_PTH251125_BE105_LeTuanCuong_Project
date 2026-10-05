package com.ridehailing.wsgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WsGatewayConfig {

    @Value("${services.location}")
    private String locationServiceUrl;

    @Value("${services.dispatch}")
    private String dispatchServiceUrl;

    @Value("${internal.key}")
    private String internalKey;

    public String locationServiceUrl() {
        return locationServiceUrl;
    }

    public String dispatchServiceUrl() {
        return dispatchServiceUrl;
    }

    public String internalKey() {
        return internalKey;
    }
}