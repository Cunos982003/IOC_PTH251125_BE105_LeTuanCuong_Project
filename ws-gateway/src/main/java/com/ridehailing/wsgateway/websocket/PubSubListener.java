package com.ridehailing.wsgateway.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.wsgateway.websocket.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PubSubListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(PubSubListener.class);
    private static final String USER_CHANNEL_PREFIX = "ws:out:";

    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public PubSubListener(SessionManager sessionManager, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        if (!channel.startsWith(USER_CHANNEL_PREFIX)) {
            return;
        }

        String userId = channel.substring(USER_CHANNEL_PREFIX.length());
        String payload = new String(message.getBody());

        // Chỉ instance đang giữ session của user này mới gửi
        var session = sessionManager.getSession(userId);
        if (session != null && session.isOpen()) {
            try {
                // Payload đã là JSON {t:type,...}, gửi thẳng
                session.sendMessage(payload);
                log.debug("Pushed message to user {} via WebSocket", userId);
            } catch (Exception e) {
                log.error("Failed to send message to user {}", userId, e);
            }
        }
    }
}
