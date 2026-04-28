package com.xiyiyun.shop.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.mvp.OrderItem;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class OrderRealtimeBroadcaster {
    private final ObjectMapper objectMapper;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public OrderRealtimeBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void addSession(WebSocketSession session) {
        sessions.add(session);
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
    }

    public void publish(OrderItem order) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(OrderRealtimeEvent.updated(order));
        } catch (JsonProcessingException ex) {
            return;
        }

        sessions.removeIf(session -> !session.isOpen() || !send(session, payload));
    }

    private boolean send(WebSocketSession session, String payload) {
        try {
            session.sendMessage(new TextMessage(payload));
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
