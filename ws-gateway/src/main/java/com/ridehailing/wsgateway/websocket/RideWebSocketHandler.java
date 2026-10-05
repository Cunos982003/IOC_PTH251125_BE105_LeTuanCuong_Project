package com.ridehailing.wsgateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.wsgateway.client.DispatchServiceClient;
import com.ridehailing.wsgateway.dto.*;
import com.ridehailing.wsgateway.security.JwtVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class RideWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RideWebSocketHandler.class);

    private final JwtVerifier jwtVerifier;
    private final SessionManager sessionManager;
    private final RateLimiter rateLimiter;
    private final LocationBatcher locationBatcher;
    private final DispatchServiceClient dispatchClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final int maxMessageSize;
    private final long authTimeoutMs;
    private final long pingIntervalMs;
    private final long pongTimeoutMs;

    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(
            2, Thread.ofVirtual().factory());

    public RideWebSocketHandler(JwtVerifier jwtVerifier,
                                SessionManager sessionManager,
                                RateLimiter rateLimiter,
                                LocationBatcher locationBatcher,
                                DispatchServiceClient dispatchClient,
                                @Value("${websocket.max-message-size:4096}") int maxMessageSize,
                                @Value("${websocket.auth-timeout-seconds:5}") int authTimeoutSec,
                                @Value("${websocket.ping-interval-seconds:25}") int pingIntervalSec,
                                @Value("${websocket.pong-timeout-seconds:60}") int pongTimeoutSec) {
        this.jwtVerifier = jwtVerifier;
        this.sessionManager = sessionManager;
        this.rateLimiter = rateLimiter;
        this.locationBatcher = locationBatcher;
        this.dispatchClient = dispatchClient;
        this.maxMessageSize = maxMessageSize;
        this.authTimeoutMs = authTimeoutSec * 1000L;
        this.pingIntervalMs = pingIntervalSec * 1000L;
        this.pongTimeoutMs = pongTimeoutSec * 1000L;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) {
        long connectedAt = System.currentTimeMillis();
        wsSession.getAttributes().put("connectedAt", connectedAt);
        wsSession.getAttributes().put("lastPong", connectedAt);

        scheduler.schedule(() -> checkAuthTimeout(wsSession), authTimeoutMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(() -> sendPing(wsSession), pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(() -> checkPongTimeout(wsSession), pongTimeoutMs, pongTimeoutMs, TimeUnit.MILLISECONDS);

        log.info("WebSocket connected: {}", wsSession.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
        String payload = message.getPayload();

        if (payload.length() > maxMessageSize) {
            sendError(wsSession, "MESSAGE_TOO_LARGE", "Message exceeds " + maxMessageSize + " bytes");
            return;
        }

        Session session = sessionManager.getSessionByWs(wsSession);

        if (session != null && !rateLimiter.allow(session.getUserId())) {
            sendError(wsSession, "RATE_LIMIT", "Too many messages");
            return;
        }

        try {
            WsMessage msg = objectMapper.readValue(payload, WsMessage.class);

            if ("auth".equals(msg.getType())) {
                handleAuth(wsSession, payload);
            } else if (session == null) {
                closeWithReason(wsSession, CloseStatus.POLICY_VIOLATION, "Not authenticated");
            } else {
                session.updateActivity();
                switch (msg.getType()) {
                    case "location" -> handleLocation(wsSession, session, payload);
                    case "accept" -> handleAccept(wsSession, session, payload);
                    default -> sendError(wsSession, "UNKNOWN_TYPE", "Unknown message type");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse message: {}", e.getMessage());
            sendError(wsSession, "INVALID_FORMAT", "Invalid JSON");
        }
    }

    private void handleAuth(WebSocketSession wsSession, String payload) {
        try {
            AuthMessage authMsg = objectMapper.readValue(payload, AuthMessage.class);
            Map<String, Object> claims = jwtVerifier.verify(authMsg.getToken());

            String userId = (String) claims.get("sub");
            String role = (String) claims.get("role");

            if (userId == null || role == null) {
                closeWithReason(wsSession, CloseStatus.POLICY_VIOLATION, "Invalid token claims");
                return;
            }

            String path = wsSession.getUri() != null ? wsSession.getUri().getPath() : "";
            boolean roleMatches = (path.contains("/driver") && "driver".equals(role)) ||
                                 (path.contains("/customer") && "customer".equals(role));

            if (!roleMatches) {
                closeWithReason(wsSession, CloseStatus.POLICY_VIOLATION, "Role mismatch");
                return;
            }

            Session session = new Session(userId, role, wsSession);
            sessionManager.register(userId, wsSession, session);
            wsSession.getAttributes().put("authenticated", true);

            log.info("Authenticated {} as {}", role, userId);
        } catch (SecurityException e) {
            closeWithReason(wsSession, CloseStatus.POLICY_VIOLATION, "Invalid token");
        } catch (Exception e) {
            log.error("Auth failed", e);
            closeWithReason(wsSession, CloseStatus.SERVER_ERROR, "Auth failed");
        }
    }

    private void handleLocation(WebSocketSession wsSession, Session session, String payload) {
        if (!"driver".equals(session.getRole())) {
            sendError(wsSession, "FORBIDDEN", "Only drivers can send location");
            return;
        }

        try {
            LocationMessage locMsg = objectMapper.readValue(payload, LocationMessage.class);
            locationBatcher.enqueue(session.getUserId(), locMsg.getLat(), locMsg.getLng(), locMsg.getSentAt());
        } catch (Exception e) {
            log.warn("Invalid location message: {}", e.getMessage());
            sendError(wsSession, "INVALID_LOCATION", "Invalid location data");
        }
    }

    private void handleAccept(WebSocketSession wsSession, Session session, String payload) {
        if (!"driver".equals(session.getRole())) {
            sendError(wsSession, "FORBIDDEN", "Only drivers can accept trips");
            return;
        }

        try {
            AcceptMessage acceptMsg = objectMapper.readValue(payload, AcceptMessage.class);
            dispatchClient.acceptTrip(acceptMsg.getTripId(), session.getUserId());
        } catch (DispatchServiceClient.DispatchException e) {
            int status = e.getStatusCode();
            if (status == 409) {
                sendError(wsSession, "TRIP_TAKEN", "Trip already accepted");
            } else if (status == 404) {
                sendError(wsSession, "TRIP_NOT_FOUND", "Trip not found");
            } else {
                sendError(wsSession, "ACCEPT_FAILED", "Failed to accept trip");
            }
        } catch (Exception e) {
            log.error("Accept failed", e);
            sendError(wsSession, "ACCEPT_FAILED", "Failed to accept trip");
        }
    }

    private void checkAuthTimeout(WebSocketSession wsSession) {
        if (!wsSession.isOpen()) {
            return;
        }

        Boolean authenticated = (Boolean) wsSession.getAttributes().get("authenticated");
        if (authenticated == null || !authenticated) {
            closeWithReason(wsSession, CloseStatus.POLICY_VIOLATION, "Auth timeout");
        }
    }

    private void sendPing(WebSocketSession wsSession) {
        if (wsSession.isOpen()) {
            try {
                wsSession.sendMessage(new PingMessage());
            } catch (Exception e) {
                log.debug("Failed to send ping", e);
            }
        }
    }

    private void checkPongTimeout(WebSocketSession wsSession) {
        if (!wsSession.isOpen()) {
            return;
        }

        Long lastPong = (Long) wsSession.getAttributes().get("lastPong");
        if (lastPong != null && System.currentTimeMillis() - lastPong > pongTimeoutMs) {
            closeWithReason(wsSession, CloseStatus.SESSION_NOT_RELIABLE, "Pong timeout");
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession wsSession, org.springframework.web.socket.PongMessage message) {
        wsSession.getAttributes().put("lastPong", System.currentTimeMillis());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        Session session = sessionManager.getSessionByWs(wsSession);
        if (session != null) {
            rateLimiter.cleanup(session.getUserId());
        }
        sessionManager.cleanupSession(wsSession);
        log.info("WebSocket closed: {} - {}", wsSession.getId(), status);
    }

    private void sendError(WebSocketSession wsSession, String code, String message) {
        try {
            ErrorResponse error = new ErrorResponse(code, message);
            String json = objectMapper.writeValueAsString(error);
            wsSession.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.error("Failed to send error", e);
        }
    }

    private void closeWithReason(WebSocketSession wsSession, CloseStatus status, String reason) {
        try {
            wsSession.close(status.withReason(reason));
        } catch (Exception e) {
            log.debug("Failed to close session", e);
        }
    }
}
