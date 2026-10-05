package com.ridehailing.wsgateway.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionManager {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Session> metadata = new ConcurrentHashMap<>();

    public void register(String userId, WebSocketSession wsSession, Session session) {
        WebSocketSession old = sessions.put(userId, wsSession);
        metadata.put(userId, session);

        if (old != null && old.isOpen()) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void unregister(String userId) {
        sessions.remove(userId);
        metadata.remove(userId);
    }

    public WebSocketSession getWebSocketSession(String userId) {
        return sessions.get(userId);
    }

    public Session getSession(String userId) {
        return metadata.get(userId);
    }

    public Session getSessionByWs(WebSocketSession wsSession) {
        String userId = findUserIdByWs(wsSession);
        return userId != null ? metadata.get(userId) : null;
    }

    public String findUserIdByWs(WebSocketSession wsSession) {
        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            if (entry.getValue().equals(wsSession)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void cleanupSession(WebSocketSession wsSession) {
        String userId = findUserIdByWs(wsSession);
        if (userId != null) {
            unregister(userId);
        }
    }
}
