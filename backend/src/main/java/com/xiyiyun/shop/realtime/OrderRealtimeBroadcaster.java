package com.xiyiyun.shop.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.ProductMonitorLogItem;
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
        publishEvent(OrderRealtimeEvent.updated(order));
    }

    public void publishProductMonitorLog(ProductMonitorLogItem log) {
        publishEvent(OrderRealtimeEvent.productMonitor(log));
    }

    private void publishEvent(OrderRealtimeEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            return;
        }

        sessions.removeIf(session -> !session.isOpen() || (canReceive(session, event) && !send(session, payload)));
    }

    private boolean canReceive(WebSocketSession session, OrderRealtimeEvent event) {
        Object role = session.getAttributes().get("role");
        if ("admin".equals(role)) {
            return true;
        }
        if (!"h5".equals(role)) {
            return false;
        }
        if (!"ORDER_UPDATED".equals(event.type()) || event.order() == null) {
            return false;
        }
        Object userId = session.getAttributes().get("userId");
        return userId instanceof Long id && id.equals(event.order().userId());
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
