package com.ridehailing.wsgateway.websocket;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

public class Session {
    private final String userId;
    private final String role;
    private final long authenticatedAt;
    private long lastActivity;
    private final WebSocketSession wsSession;

    public Session(String userId, String role, WebSocketSession wsSession) {
        this.userId = userId;
        this.role = role;
        this.wsSession = wsSession;
        this.authenticatedAt = System.currentTimeMillis();
        this.lastActivity = authenticatedAt;
    }

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public long getAuthenticatedAt() {
        return authenticatedAt;
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public void updateActivity() {
        this.lastActivity = System.currentTimeMillis();
    }

    public boolean isOpen() {
        return wsSession != null && wsSession.isOpen();
    }

    public void sendMessage(String payload) throws IOException {
        if (isOpen()) {
            wsSession.sendMessage(new TextMessage(payload));
        }
    }

    public WebSocketSession getWebSocketSession() {
        return wsSession;
    }
}
