package com.ridehailing.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gateway")
public class GatewayConfig {

    private String jwtSecret;
    private String internalKey;
    private String userServiceUrl;
    private String dispatchServiceUrl;
    private String pricingServiceUrl;
    private String paymentServiceUrl;
    private Cors cors = new Cors();
    private RateLimit rateLimit = new RateLimit();
    private Proxy proxy = new Proxy();

    @jakarta.annotation.PostConstruct
    void validateSecrets() {
        if (jwtSecret == null || jwtSecret.isBlank() || internalKey == null || internalKey.isBlank()) {
            throw new IllegalStateException("JWT_SECRET and INTERNAL_KEY must be set");
        }
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getInternalKey() {
        return internalKey;
    }

    public void setInternalKey(String internalKey) {
        this.internalKey = internalKey;
    }

    public String getUserServiceUrl() {
        return userServiceUrl;
    }

    public void setUserServiceUrl(String userServiceUrl) {
        this.userServiceUrl = userServiceUrl;
    }

    public String getDispatchServiceUrl() {
        return dispatchServiceUrl;
    }

    public void setDispatchServiceUrl(String dispatchServiceUrl) {
        this.dispatchServiceUrl = dispatchServiceUrl;
    }

    public String getPricingServiceUrl() {
        return pricingServiceUrl;
    }

    public void setPricingServiceUrl(String pricingServiceUrl) {
        this.pricingServiceUrl = pricingServiceUrl;
    }

    public String getPaymentServiceUrl() {
        return paymentServiceUrl;
    }

    public void setPaymentServiceUrl(String paymentServiceUrl) {
        this.paymentServiceUrl = paymentServiceUrl;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Proxy getProxy() {
        return proxy;
    }

    public void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    public static class Cors {
        private String allowedOrigins;
        private String allowedMethods = "GET,POST,PUT,DELETE,OPTIONS";
        private String allowedHeaders = "*";
        private boolean allowCredentials = true;

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public String getAllowedMethods() {
            return allowedMethods;
        }

        public void setAllowedMethods(String allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        public String getAllowedHeaders() {
            return allowedHeaders;
        }

        public void setAllowedHeaders(String allowedHeaders) {
            this.allowedHeaders = allowedHeaders;
        }

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public void setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
        }
    }

    public static class RateLimit {
        private int authRpm = 60;
        private int userRpm = 300;

        public int getAuthRpm() {
            return authRpm;
        }

        public void setAuthRpm(int authRpm) {
            this.authRpm = authRpm;
        }

        public int getUserRpm() {
            return userRpm;
        }

        public void setUserRpm(int userRpm) {
            this.userRpm = userRpm;
        }
    }

    public static class Proxy {
        private int connectTimeoutMs = 1000;
        private int readTimeoutMs = 5000;
        private int readTimeoutCreateRideMs = 8000;
        private int maxBodySizeBytes = 65536;

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getReadTimeoutCreateRideMs() {
            return readTimeoutCreateRideMs;
        }

        public void setReadTimeoutCreateRideMs(int readTimeoutCreateRideMs) {
            this.readTimeoutCreateRideMs = readTimeoutCreateRideMs;
        }

        public int getMaxBodySizeBytes() {
            return maxBodySizeBytes;
        }

        public void setMaxBodySizeBytes(int maxBodySizeBytes) {
            this.maxBodySizeBytes = maxBodySizeBytes;
        }
    }
}
