package com.ridehailing.apigateway.controller;

import com.ridehailing.apigateway.config.GatewayConfig;
import com.ridehailing.apigateway.service.ProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final ProxyService proxyService;
    private final GatewayConfig gatewayConfig;

    public GatewayController(ProxyService proxyService, GatewayConfig gatewayConfig) {
        this.proxyService = proxyService;
        this.gatewayConfig = gatewayConfig;
    }

    @RequestMapping("/api/v1/auth/**")
    public ResponseEntity<byte[]> proxyAuth(HttpServletRequest request) throws Exception {
        return proxyWithLogging(request, gatewayConfig.getUserServiceUrl());
    }

    @RequestMapping("/api/v1/users/**")
    public ResponseEntity<byte[]> proxyUsers(HttpServletRequest request) throws Exception {
        return proxyWithLogging(request, gatewayConfig.getUserServiceUrl());
    }

    @RequestMapping("/api/v1/drivers/**")
    public ResponseEntity<byte[]> proxyDrivers(HttpServletRequest request) throws Exception {
        return proxyWithLogging(request, gatewayConfig.getUserServiceUrl());
    }

    @RequestMapping("/api/v1/rides/**")
    public ResponseEntity<byte[]> proxyRides(HttpServletRequest request) throws Exception {
        return proxyWithLogging(request, gatewayConfig.getDispatchServiceUrl());
    }

    @RequestMapping("/api/v1/trips/**")
    public ResponseEntity<byte[]> proxyTrips(HttpServletRequest request) throws Exception {
        return proxyWithLogging(request, gatewayConfig.getDispatchServiceUrl());
    }

    @RequestMapping("/api/v1/quote")
    public ResponseEntity<byte[]> proxyQuote(HttpServletRequest request) throws Exception {
        return proxyWithLogging(request, gatewayConfig.getPricingServiceUrl());
    }

    @RequestMapping("/api/v1/wallet/**")
    public ResponseEntity<byte[]> proxyWallet(HttpServletRequest request) throws Exception {
        return proxyWithLogging(request, gatewayConfig.getPaymentServiceUrl());
    }

    private ResponseEntity<byte[]> proxyWithLogging(HttpServletRequest request, String targetServiceUrl) throws Exception {
        String method = request.getMethod();
        String path = request.getRequestURI();
        String requestId = (String) request.getAttribute("requestId");
        Long userId = (Long) request.getAttribute("userId");

        log.info("Request: method={}, path={}, requestId={}, userId={}",
            method, path, requestId, userId);

        try {
            ResponseEntity<byte[]> response = proxyService.proxyRequest(request, targetServiceUrl);
            log.info("Response: status={}, requestId={}", response.getStatusCode().value(), requestId);
            return response;
        } catch (Exception e) {
            log.error("Proxy error: requestId={}, error={}", requestId, e.getMessage());
            throw e;
        }
    }
}
