package com.ridehailing.wsgateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.wsgateway.dto.PushRequest;
import com.ridehailing.wsgateway.dto.PushResponse;
import com.ridehailing.wsgateway.dto.SetRouteRequest;
import com.ridehailing.wsgateway.routing.RoutingService;
import com.ridehailing.wsgateway.websocket.SessionManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/internal")
public class InternalController {
    private static final String USER_CHANNEL_PREFIX = "ws:out:";

    private final RoutingService routingService;
    private final SessionManager sessionManager;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public InternalController(RoutingService routingService,
                              SessionManager sessionManager,
                              StringRedisTemplate redis,
                              ObjectMapper objectMapper) {
        this.routingService = routingService;
        this.sessionManager = sessionManager;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/push")
    public ResponseEntity<PushResponse> push(@RequestBody PushRequest request) {
        try {
            // Build message: {t:type, ...payload}
            Map<String, Object> message = new HashMap<>();
            message.put("t", request.type());
            if (request.payload() != null) {
                message.putAll(request.payload());
            }

            String json = objectMapper.writeValueAsString(message);

            // PUBLISH to ws:out:{userId}
            redis.convertAndSend(USER_CHANNEL_PREFIX + request.userId(), json);

            // Check if user is online on THIS instance
            var session = sessionManager.getSession(request.userId());
            boolean delivered = session != null && session.isOpen();

            return ResponseEntity.ok(new PushResponse(delivered));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new PushResponse(false));
        }
    }

    @PutMapping("/routes/{driverId}")
    public ResponseEntity<Map<String, Boolean>> setRoute(@PathVariable String driverId,
                                         @RequestBody SetRouteRequest request) {
        routingService.setRoute(driverId, request.customerId());
        return ResponseEntity.ok(Map.of("mapped", true));
    }

    @DeleteMapping("/routes/{driverId}")
    public ResponseEntity<Map<String, Boolean>> deleteRoute(@PathVariable String driverId) {
        routingService.deleteRoute(driverId);
        return ResponseEntity.ok(Map.of("deleted", true));
    }
}
